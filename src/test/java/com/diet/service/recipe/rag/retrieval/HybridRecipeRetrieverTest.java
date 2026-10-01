package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeSparseIndex;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRecipeRetrieverTest {

    @Test
    void parallelBranchesOverlapAndMatchSerialResults() {
        ConcurrencyProbe probe = new ConcurrencyProbe();
        HybridRecipeRetriever retriever = retriever(probe, false);
        RecipeQuery query = new RecipeQuery("番茄炒蛋怎么做", List.of(), List.of(), List.of(), null);

        var parallel = retriever.retrieve(query, RecipeRetrievalMode.PARALLEL_HYBRID).block();
        int parallelMaximum = probe.maximum.get();
        probe.reset();
        var serial = retriever.retrieve(query, RecipeRetrievalMode.SERIAL_HYBRID).block();

        assertThat(parallel.recipes()).isEqualTo(serial.recipes());
        assertThat(parallelMaximum).isEqualTo(2);
        assertThat(probe.maximum.get()).isEqualTo(1);
        assertThat(parallel.diagnostics().actualMode()).isEqualTo(RecipeRetrievalMode.PARALLEL_HYBRID);
        assertThat(parallel.diagnostics().sparseDurationMs()).isPositive();
        assertThat(parallel.diagnostics().denseDurationMs()).isPositive();
    }

    @Test
    void degradesToDenseWhenSparseBranchFails() {
        HybridRecipeRetriever retriever = retriever(new ConcurrencyProbe(), true);

        var result = retriever.retrieve(
                new RecipeQuery("番茄炒蛋", List.of(), List.of(), List.of(), null),
                RecipeRetrievalMode.PARALLEL_HYBRID).block();

        assertThat(result.recipes()).extracting(recipe -> recipe.recipe().recipeId())
                .containsExactly("recipe-b", "recipe-a");
        assertThat(result.diagnostics().actualMode()).isEqualTo(RecipeRetrievalMode.DENSE_ONLY);
        assertThat(result.diagnostics().degradationReasons()).anyMatch(reason -> reason.startsWith("sparse_failed:"));
    }

    private HybridRecipeRetriever retriever(ConcurrencyProbe probe, boolean failSparse) {
        List<RecipeHit> sparseHits = List.of(hit("a-s", "recipe-a", 1, "BM25"), hit("b-s", "recipe-b", 2, "BM25"));
        List<RecipeHit> denseHits = List.of(hit("b-d", "recipe-b", 1, "DENSE"), hit("a-d", "recipe-a", 2, "DENSE"));
        RecipeSparseIndex sparse = new RecipeSparseIndex() {
            @Override public void rebuild(List<RecipeDocument> documents, List<RecipeChunk> chunks) { }
            @Override public List<RecipeHit> search(String query, int topK) {
                if (failSparse) throw new IllegalStateException("lucene unavailable");
                return probe.call(sparseHits);
            }
        };
        RecipeEmbeddingModel embedding = new RecipeEmbeddingModel() {
            @Override public Mono<double[]> embed(String text) { return Mono.fromCallable(() -> probe.call(new double[]{1, 0, 0})); }
            @Override public String modelName() { return "fixture"; }
            @Override public int dimensions() { return 3; }
        };
        RecipeVectorIndex vector = new RecipeVectorIndex() {
            @Override public void replaceVersion(String version, List<RecipeChunk> chunks, List<double[]> vectors) { }
            @Override public Mono<List<RecipeHit>> search(RecipeQuery query, double[] value, int topK) { return Mono.just(denseHits); }
        };
        List<RecipeDocument> documents = List.of(document("recipe-a", "西红柿炒鸡蛋"), document("recipe-b", "番茄蛋汤"));
        return new HybridRecipeRetriever(sparse, embedding, vector, new RecipeParentResolver(documents),
                Duration.ofSeconds(2), 10, 10, 5, 60);
    }

    private RecipeDocument document(String id, String title) {
        return new RecipeDocument(id, title, List.of(), List.of("番茄", "鸡蛋"), List.of("烹饪"),
                "简介", Map.of("操作", "烹饪"), title + ".md", "commit", "hash-" + id);
    }

    private RecipeHit hit(String chunkId, String parentId, int rank, String channel) {
        return new RecipeHit(chunkId, parentId, parentId, RecipeChunk.ChunkType.STEPS,
                "evidence", 1.0 / rank, rank, channel);
    }

    private static final class ConcurrencyProbe {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximum = new AtomicInteger();

        private <T> T call(T value) {
            int running = active.incrementAndGet();
            maximum.accumulateAndGet(running, Math::max);
            try {
                Thread.sleep(120);
                return value;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            } finally {
                active.decrementAndGet();
            }
        }

        private void reset() { active.set(0); maximum.set(0); }
    }
}
