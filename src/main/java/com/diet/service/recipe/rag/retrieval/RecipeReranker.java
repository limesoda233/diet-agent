package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface RecipeReranker {
    Mono<List<RetrievedRecipe>> rerank(RecipeQuery query, List<RetrievedRecipe> recipes);

    /** Legacy/identity adapters remain source-compatible; real admission providers override this. */
    default Mono<RecipeRerankResult> rerankDetailed(RecipeQuery query, List<RetrievedRecipe> recipes) {
        return Mono.defer(() -> rerank(query, recipes))
                .switchIfEmpty(Mono.error(new IllegalStateException("rerank_empty_signal")))
                .map(result -> RecipeRerankResult.bypassed(result, recipes.size()));
    }

    static RecipeReranker withFallback(RecipeReranker delegate, Duration timeout) {
        return (query, recipes) -> delegate.rerank(query, recipes)
                .timeout(timeout)
                .onErrorReturn(recipes);
    }
}
