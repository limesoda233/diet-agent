package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RecipeVectorIndex {
    void replaceVersion(String indexVersion, List<RecipeChunk> chunks, List<double[]> vectors);
    default void stageVersion(String indexVersion, List<RecipeChunk> chunks, List<double[]> vectors) {
        throw new UnsupportedOperationException("staged vector index builds are not supported");
    }
    default void activateVersion(String indexVersion) {
        throw new UnsupportedOperationException("vector index activation is not supported");
    }
    default void discardVersion(String indexVersion) {
    }
    default String activeVersion() {
        return null;
    }
    Mono<List<RecipeHit>> search(RecipeQuery query, double[] vector, int topK);
}
