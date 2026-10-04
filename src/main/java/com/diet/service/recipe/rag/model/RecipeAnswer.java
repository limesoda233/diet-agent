package com.diet.service.recipe.rag.model;

import java.util.List;
import java.util.Map;

public record RecipeAnswer(
        String text,
        List<RecipeCitation> citations,
        boolean refused,
        Map<String, Object> diagnostics,
        List<RecipeEvidenceContext> evidenceContexts) {

    public RecipeAnswer(String text, List<RecipeCitation> citations,
                        boolean refused, Map<String, Object> diagnostics) {
        this(text, citations, refused, diagnostics, List.of());
    }

    public RecipeAnswer {
        text = text == null ? "" : text.trim();
        citations = citations == null ? List.of() : List.copyOf(citations);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        evidenceContexts = evidenceContexts == null ? List.of() : List.copyOf(evidenceContexts);
    }

    public static RecipeAnswer refused(String text, Map<String, Object> diagnostics) {
        return refused(text, diagnostics, List.of());
    }

    public static RecipeAnswer refused(String text, Map<String, Object> diagnostics,
                                       List<RecipeEvidenceContext> evidenceContexts) {
        return new RecipeAnswer(text, List.of(), true, diagnostics, evidenceContexts);
    }
}
