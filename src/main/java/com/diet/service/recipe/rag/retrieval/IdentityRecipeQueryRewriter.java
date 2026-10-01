package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import reactor.core.publisher.Mono;

public class IdentityRecipeQueryRewriter implements RecipeQueryRewriter {
    @Override
    public Mono<RecipeQuery> rewrite(RecipeQuery original) {
        return Mono.just(original);
    }
}
