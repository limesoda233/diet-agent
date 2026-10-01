package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface RecipeReranker {
    Mono<List<RetrievedRecipe>> rerank(RecipeQuery query, List<RetrievedRecipe> recipes);

    static RecipeReranker withFallback(RecipeReranker delegate, Duration timeout) {
        return (query, recipes) -> delegate.rerank(query, recipes)
                .timeout(timeout)
                .onErrorReturn(recipes);
    }
}
