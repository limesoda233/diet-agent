package com.diet.service.recipe.rag.model;

public record RecipeEvidenceContext(String id, String recipeId, String text) {
    public RecipeEvidenceContext {
        id = id == null ? "" : id.trim();
        recipeId = recipeId == null ? "" : recipeId.trim();
        text = text == null ? "" : text;
        if (id.isBlank() || recipeId.isBlank() || text.isBlank()) {
            throw new IllegalArgumentException("recipe evidence context fields must be nonblank");
        }
    }
}
