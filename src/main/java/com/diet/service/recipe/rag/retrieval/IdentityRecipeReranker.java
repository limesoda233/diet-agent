package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import reactor.core.publisher.Mono;

import java.util.List;

public class IdentityRecipeReranker implements RecipeReranker {
    @Override
    public Mono<List<RetrievedRecipe>> rerank(RecipeQuery query, List<RetrievedRecipe> recipes) {
        return Mono.just(recipes);
    }
}
