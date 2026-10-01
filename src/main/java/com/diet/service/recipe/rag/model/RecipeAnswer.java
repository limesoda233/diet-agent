package com.diet.service.recipe.rag.model;

import java.util.List;
import java.util.Map;

public record RecipeAnswer(
        String text,
        List<RecipeCitation> citations,
        boolean refused,
        Map<String, Object> diagnostics) {

    public RecipeAnswer {
        text = text == null ? "" : text.trim();
        citations = citations == null ? List.of() : List.copyOf(citations);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public static RecipeAnswer refused(String text, Map<String, Object> diagnostics) {
        return new RecipeAnswer(text, List.of(), true, diagnostics);
    }
}
