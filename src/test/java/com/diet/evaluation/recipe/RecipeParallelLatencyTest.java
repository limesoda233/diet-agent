package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeSparseIndex;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeParentResolver;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeParallelLatencyTest {
    @Test
    void parallelHybridOverlapsIndependentSparseAndDenseBranches() {
        RecipeDocument document = new RecipeDocument("r1", "番茄炒蛋", List.of(), List.of("番茄", "鸡蛋"),
                List.of("炒制"), "", Map.of(), "path.md", "commit", "hash");
        RecipeHit hit = new RecipeHit("c1", "r1", "番茄炒蛋", RecipeChunk.ChunkType.STEPS,
                "炒制", 1, 1, "TEST");
        RecipeSparseIndex sparse = new RecipeSparseIndex() {
            @Override public void rebuild(List<RecipeDocument> documents, List<RecipeChunk> chunks) { }
            @Override public List<RecipeHit> search(String query, int topK) { pause(140); return List.of(hit); }
        };
        RecipeEmbeddingModel embedding = new RecipeEmbeddingModel() {
            @Override public Mono<double[]> embed(String text) { return Mono.fromCallable(() -> { pause(70); return new double[]{1}; }); }
            @Override public String modelName() { return "latency-probe"; }
            @Override public int dimensions() { return 1; }
        };
        RecipeVectorIndex vector = new RecipeVectorIndex() {
            @Override public void replaceVersion(String indexVersion, List<RecipeChunk> chunks, List<double[]> vectors) { }
            @Override public Mono<List<RecipeHit>> search(RecipeQuery query, double[] value, int topK) {
                return Mono.fromCallable(() -> { pause(70); return List.of(hit); });
            }
        };
        var retriever = new HybridRecipeRetriever(sparse, embedding, vector,
                new RecipeParentResolver(List.of(document)), Duration.ofSeconds(2), 5, 5, 5, 60);
        RecipeQuery query = new RecipeQuery("番茄炒蛋", List.of(), List.of(), List.of(), null);

        var serial = retriever.retrieve(query, RecipeRetrievalMode.SERIAL_HYBRID).block();
        var parallel = retriever.retrieve(query, RecipeRetrievalMode.PARALLEL_HYBRID).block();

        assertThat(parallel.recipes()).isEqualTo(serial.recipes());
        assertThat(serial.diagnostics().totalDurationMs() - parallel.diagnostics().totalDurationMs()).isGreaterThan(80);
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
