package com.diet.service.recipe.rag.model;

import java.util.List;

public record RetrievedRecipe(RecipeDocument recipe, double score, List<RecipeHit> evidence) {
    public RetrievedRecipe {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
