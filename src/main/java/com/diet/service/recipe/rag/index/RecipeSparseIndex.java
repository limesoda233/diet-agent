package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;

import java.util.List;

public interface RecipeSparseIndex extends AutoCloseable {

    void rebuild(List<RecipeDocument> documents, List<RecipeChunk> chunks);

    default void stageVersion(String version, List<RecipeDocument> documents, List<RecipeChunk> chunks) {
        throw new UnsupportedOperationException("staged sparse index builds are not supported");
    }

    default void activateVersion(String version) {
        throw new UnsupportedOperationException("sparse index activation is not supported");
    }

    default void discardVersion(String version) {
    }

    default String activeVersion() {
        return null;
    }

    List<RecipeHit> search(String query, int topK);

    @Override
    default void close() {
    }
}
