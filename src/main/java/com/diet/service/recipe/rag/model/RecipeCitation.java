package com.diet.service.recipe.rag.model;

public record RecipeCitation(
        String id,
        String recipeId,
        String title,
        String sourcePath,
        String sourceCommit,
        String contentHash) {
}
