package com.diet.service.recipe.rag.index;

import reactor.core.publisher.Mono;

public interface RecipeEmbeddingModel {
    Mono<double[]> embed(String text);
    String modelName();
    int dimensions();
}
