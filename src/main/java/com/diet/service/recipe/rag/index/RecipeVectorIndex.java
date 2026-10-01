package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RecipeVectorIndex {
    void replaceVersion(String indexVersion, List<RecipeChunk> chunks, List<double[]> vectors);
    Mono<List<RecipeHit>> search(RecipeQuery query, double[] vector, int topK);
}
