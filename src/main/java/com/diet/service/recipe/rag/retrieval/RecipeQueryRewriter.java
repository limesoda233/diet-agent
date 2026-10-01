package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

@FunctionalInterface
public interface RecipeQueryRewriter {
    Mono<RecipeQuery> rewrite(RecipeQuery original);

    static RecipeQueryRewriter withFallback(RecipeQueryRewriter delegate, Duration timeout) {
        return original -> delegate.rewrite(original)
                .timeout(timeout)
                .map(rewritten -> preserveConstraints(original, rewritten))
                .onErrorReturn(original);
    }

    private static RecipeQuery preserveConstraints(RecipeQuery original, RecipeQuery rewritten) {
        if (rewritten == null) return original;
        return new RecipeQuery(
                rewritten.text().isBlank() ? original.text() : rewritten.text(),
                union(original.requiredIngredients(), rewritten.requiredIngredients()),
                union(original.excludedIngredients(), rewritten.excludedIngredients()),
                union(original.requiredTools(), rewritten.requiredTools()),
                rewritten.maxMinutes() == null ? original.maxMinutes() : rewritten.maxMinutes());
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }
}
