package com.diet.service.recipe.rag.model;

import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;

import java.util.List;

public record RecipeRetrievalResult(List<RetrievedRecipe> recipes, Diagnostics diagnostics) {
    public RecipeRetrievalResult {
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
    }

    public record Diagnostics(
            RecipeRetrievalMode requestedMode,
            RecipeRetrievalMode actualMode,
            long sparseDurationMs,
            long denseDurationMs,
            long totalDurationMs,
            int sparseCandidateCount,
            int denseCandidateCount,
            List<String> degradationReasons,
            List<String> finalParentRecipeIds) {

        public Diagnostics {
            degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
            finalParentRecipeIds = finalParentRecipeIds == null ? List.of() : List.copyOf(finalParentRecipeIds);
        }
    }
}
