package com.diet.service.recipe;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.video.CookingVideo;
import com.diet.service.video.CookingVideoSearchService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 外层并行：文字菜谱与视频链接互相独立，共享的是预先解析好的查询，而非另一支结果。
 * 每支先转换成 BranchOutcome，再 zip，保证一支失败不会吞掉另一支的成功结果。
 * 返回结果后由 Orchestrator 决定显示文字、视频或不可用提示。
 * 注意 zip 会等待两支终态，当前不是“先文字、后追加视频”的流式接口。
 */
public final class RecipeVideoCoordinator {
    private final CookingVideoSearchService videos;

    public RecipeVideoCoordinator(CookingVideoSearchService videos) {
        this.videos = videos;
    }

    public Mono<Result> execute(Supplier<RecipeAnswer> recipeCall, String recipeText) {
        return execute(recipeCall, () -> recipeVideoRequest(recipeText));
    }

    public CookingVideoSearchService.VideoSearchRequest recipeVideoRequest(String recipeText) {
        return videos.forRecipe(recipeText);
    }

    public Mono<Result> executePrepared(Supplier<RecipeAnswer> recipeCall,
                                       CookingVideoSearchService.VideoSearchRequest videoRequest) {
        return execute(recipeCall, () -> videoRequest);
    }

    private Mono<Result> execute(Supplier<RecipeAnswer> recipeCall,
                                 Supplier<CookingVideoSearchService.VideoSearchRequest> videoRequest) {
        // RecipeQueryService 内含 block，同步工作必须调度到 boundedElastic。
        // 计时从订阅开始，包含线程调度等待；客户端内部诊断计时的范围可能不同。
        Mono<BranchOutcome<RecipeAnswer>> recipe = Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.fromCallable(() -> Objects.requireNonNull(recipeCall.get()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(answer -> recipeOutcome(answer, elapsedMs(started)))
                    .onErrorResume(error -> Mono.just(new BranchOutcome<>(
                            BranchStatus.FAILED, null, error.getClass().getSimpleName(),
                            elapsedMs(started))));
        });

        Mono<BranchOutcome<List<CookingVideo>>> video = Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.defer(() -> videos.search(videoRequest.get()))
                    .map(found -> new BranchOutcome<>(
                            found.isEmpty() ? BranchStatus.EMPTY : BranchStatus.SUCCESS,
                            List.copyOf(found), "", elapsedMs(started)))
                    .switchIfEmpty(Mono.fromSupplier(() -> new BranchOutcome<>(
                            BranchStatus.EMPTY, List.of(), "", elapsedMs(started))))
                    .onErrorResume(error -> Mono.just(new BranchOutcome<>(
                            videoStatus(error), List.of(), error.getClass().getSimpleName(),
                            elapsedMs(started))));
        });

        // 实际耗时接近较慢一支，而不是两支相加；视频内部仍有独立业务超时。
        return Mono.zip(recipe, video, Result::new);
    }

    public enum BranchStatus { SUCCESS, REFUSED, EMPTY, DISABLED, TIMEOUT, FAILED }

    public record BranchOutcome<T>(BranchStatus status, T value,
                                   String errorType, long durationMs) { }

    public record Result(BranchOutcome<RecipeAnswer> recipe,
                         BranchOutcome<List<CookingVideo>> video) { }

    private BranchOutcome<RecipeAnswer> recipeOutcome(RecipeAnswer answer, long durationMs) {
        if ("retrieval_failed".equals(answer.diagnostics().get("route"))
                || "relevance_failed".equals(answer.diagnostics().get("route"))) {
            Object error = answer.diagnostics().get("error");
            String errorType = error instanceof String name
                    && name.matches("[A-Za-z][A-Za-z0-9]{0,63}(Exception|Error)") ? name : "UnknownError";
            return new BranchOutcome<>(BranchStatus.FAILED, null, errorType, durationMs);
        }
        return new BranchOutcome<>(answer.refused() ? BranchStatus.REFUSED : BranchStatus.SUCCESS,
                answer, "", durationMs);
    }

    private BranchStatus videoStatus(Throwable error) {
        if (error instanceof TimeoutException) return BranchStatus.TIMEOUT;
        if ("video_search_disabled".equals(error.getMessage())) return BranchStatus.DISABLED;
        return BranchStatus.FAILED;
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
