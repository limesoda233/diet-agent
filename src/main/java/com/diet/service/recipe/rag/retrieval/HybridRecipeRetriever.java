package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeSparseIndex;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 公共菜谱的检索编排入口，不参与 MySQL 餐食推荐。
 * <p>每次订阅重新计时。BM25 搜关键词，Dense 先生成查询向量再查 Qdrant；
 * 两支结果先转成包含错误信息的 BranchResult，避免一支异常立即取消另一支。
 * 融合后按父菜谱恢复正文、核对约束，输出包含分支耗时和降级原因的诊断信息。</p>
 */
public class HybridRecipeRetriever {
    private final RecipeSparseIndex sparseIndex;
    private final RecipeEmbeddingModel embeddingModel;
    private final RecipeVectorIndex vectorIndex;
    private final RecipeParentResolver parentResolver;
    private final Duration branchTimeout;
    private final int sparseTopK;
    private final int denseTopK;
    private final int finalTopK;
    private final int rrfRankConstant;

    public HybridRecipeRetriever(
            RecipeSparseIndex sparseIndex,
            RecipeEmbeddingModel embeddingModel,
            RecipeVectorIndex vectorIndex,
            RecipeParentResolver parentResolver,
            Duration branchTimeout,
            int sparseTopK,
            int denseTopK,
            int finalTopK,
            int rrfRankConstant) {
        this.sparseIndex = sparseIndex;
        this.embeddingModel = embeddingModel;
        this.vectorIndex = vectorIndex;
        this.parentResolver = parentResolver;
        this.branchTimeout = branchTimeout;
        this.sparseTopK = sparseTopK;
        this.denseTopK = denseTopK;
        this.finalTopK = finalTopK;
        this.rrfRankConstant = rrfRankConstant;
    }

    /**
     * 返回冷 Publisher：构造 Mono 不等于已经执行检索，订阅或 block 才会启动。
     * SERIAL_HYBRID 用于对照实验；PARALLEL_HYBRID 的 zip 并行订阅两支，等待两支终态。
     * finalTopK 是约束过滤后的父菜谱数量，而非原始子块数量。
     */
    public Mono<RecipeRetrievalResult> retrieve(RecipeQuery query, RecipeRetrievalMode mode) {
        if (query == null || query.text().isBlank()) return Mono.error(new IllegalArgumentException("recipe query must not be blank"));
        RecipeRetrievalMode requested = mode == null ? RecipeRetrievalMode.PARALLEL_HYBRID : mode;
        return Mono.defer(() -> {
            long started = System.nanoTime();
            Mono<BranchResult> sparse = sparseBranch(query);
            Mono<BranchResult> dense = denseBranch(query);
            Mono<Branches> branches = switch (requested) {
                case SPARSE_ONLY -> sparse.map(value -> new Branches(value, BranchResult.skippedBranch()));
                case DENSE_ONLY -> dense.map(value -> new Branches(BranchResult.skippedBranch(), value));
                case SERIAL_HYBRID -> sparse.flatMap(sparseResult ->
                        dense.map(denseResult -> new Branches(sparseResult, denseResult)));
                case PARALLEL_HYBRID -> Mono.zip(sparse, dense, Branches::new);
            };
            return branches.map(value -> combine(query, requested, value, elapsedMs(started)));
        });
    }

    private Mono<BranchResult> sparseBranch(RecipeQuery query) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            // Lucene 是同步操作，放入 boundedElastic，避免阻塞 Reactor 的事件线程。
            return Mono.fromCallable(() -> sparseIndex.search(query.text(), sparseTopK))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(branchTimeout)
                    .map(hits -> BranchResult.success(hits, elapsedMs(started)))
                    .onErrorResume(error -> Mono.just(BranchResult.failure(error, elapsedMs(started))));
        });
    }

    private Mono<BranchResult> denseBranch(RecipeQuery query) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            // Dense 的业务超时覆盖 embedding + 向量搜索，不是给每一步各一份预算。
            // embeddingText 附带明确约束；最终能否满足约束仍由父块过滤核对。
            return embeddingModel.embed(query.embeddingText())
                    .flatMap(vector -> vectorIndex.search(query, vector, denseTopK))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(branchTimeout)
                    .map(hits -> BranchResult.success(hits, elapsedMs(started)))
                    .onErrorResume(error -> Mono.just(BranchResult.failure(error, elapsedMs(started))));
        });
    }

    private RecipeRetrievalResult combine(RecipeQuery query, RecipeRetrievalMode requested,
                                           Branches branches, long totalDurationMs) {
        BranchResult sparse = branches.sparse();
        BranchResult dense = branches.dense();
        boolean sparseUsable = !sparse.skipped() && sparse.error() == null;
        boolean denseUsable = !dense.skipped() && dense.error() == null;
        // 成功返回空列表与依赖故障不同。双支故障禁止继续生成，空证据由问答层拒答。
        if (!sparseUsable && !denseUsable) {
            throw new RecipeRagDependencyException("recipe_retrieval_all_branches_failed");
        }

        List<String> degradation = new ArrayList<>();
        if (sparse.error() != null) degradation.add("sparse_failed:" + errorCode(sparse.error()));
        if (dense.error() != null) degradation.add("dense_failed:" + errorCode(dense.error()));
        RecipeRetrievalMode actual = actualMode(requested, sparseUsable, denseUsable);
        List<RecipeHit> sparseHits = sparseUsable ? sparse.hits() : List.of();
        List<RecipeHit> denseHits = denseUsable ? dense.hits() : List.of();
        List<RecipeHit> fused = RecipeRrf.fuse(sparseHits, denseHits, rrfRankConstant);
        List<RecipeHit> evidence = new ArrayList<>(sparseHits);
        evidence.addAll(denseHits);
        List<RetrievedRecipe> recipes = parentResolver.resolve(query, fused, evidence, finalTopK);
        var diagnostics = new RecipeRetrievalResult.Diagnostics(
                requested, actual, sparse.durationMs(), dense.durationMs(), totalDurationMs,
                sparseHits.size(), denseHits.size(), degradation,
                recipes.stream().map(recipe -> recipe.recipe().recipeId()).toList());
        return new RecipeRetrievalResult(recipes, diagnostics);
    }

    private RecipeRetrievalMode actualMode(RecipeRetrievalMode requested, boolean sparse, boolean dense) {
        if (sparse && dense) return requested;
        return sparse ? RecipeRetrievalMode.SPARSE_ONLY : RecipeRetrievalMode.DENSE_ONLY;
    }

    private String errorCode(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ":" + message);
    }

    private long elapsedMs(long started) {
        return Math.max(1, (System.nanoTime() - started) / 1_000_000);
    }

    private record Branches(BranchResult sparse, BranchResult dense) {
    }

    private record BranchResult(List<RecipeHit> hits, Throwable error, long durationMs, boolean skipped) {
        static BranchResult success(List<RecipeHit> hits, long durationMs) {
            return new BranchResult(hits == null ? List.of() : List.copyOf(hits), null, durationMs, false);
        }

        static BranchResult failure(Throwable error, long durationMs) {
            return new BranchResult(List.of(), error, durationMs, false);
        }

        static BranchResult skippedBranch() {
            return new BranchResult(List.of(), null, 0, true);
        }
    }
}
