package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import reactor.core.publisher.Mono;

import java.time.Duration;

@FunctionalInterface
public interface RecipeQueryRewriter {
    Mono<RecipeQuery> rewrite(RecipeQuery original);

    static RecipeQueryRewriter withFallback(RecipeQueryRewriter delegate, Duration timeout) {
        return original -> Mono.defer(() -> delegate.rewrite(original))
                .switchIfEmpty(Mono.just(original))
                .timeout(timeout)
                .map(rewritten -> preserveConstraints(original, rewritten))
                .onErrorReturn(original);
    }

    private static RecipeQuery preserveConstraints(RecipeQuery original, RecipeQuery rewritten) {
        if (rewritten == null || rewritten == original) return original;
        return new RecipeQuery(
                rewritten.text().isBlank() ? original.text() : rewritten.text(),
                original.requiredIngredients(),
                original.excludedIngredients(),
                original.requiredTools(),
                original.maxMinutes());
    }
}
