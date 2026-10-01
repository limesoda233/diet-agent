package com.diet.service.recipe.rag;

public class RecipeRagDependencyException extends RuntimeException {
    public RecipeRagDependencyException(String message) {
        super(message);
    }

    public RecipeRagDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
