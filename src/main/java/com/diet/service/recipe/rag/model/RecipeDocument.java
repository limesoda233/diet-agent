package com.diet.service.recipe.rag.model;

import java.util.List;
import java.util.Map;

public record RecipeDocument(
        String recipeId,
        String title,
        List<String> aliases,
        List<String> ingredients,
        List<String> steps,
        String introduction,
        Map<String, String> sections,
        String relativePath,
        String sourceCommit,
        String contentHash) {

    public RecipeDocument {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        steps = steps == null ? List.of() : List.copyOf(steps);
        sections = sections == null ? Map.of() : Map.copyOf(sections);
    }
}
