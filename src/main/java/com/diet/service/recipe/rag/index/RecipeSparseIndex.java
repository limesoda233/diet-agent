package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;

import java.util.List;

public interface RecipeSparseIndex extends AutoCloseable {

    void rebuild(List<RecipeDocument> documents, List<RecipeChunk> chunks);

    List<RecipeHit> search(String query, int topK);

    @Override
    default void close() {
    }
}
