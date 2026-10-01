package com.diet.service.recipe.rag.model;

import java.util.List;

public record RecipeQuery(
        String text,
        List<String> requiredIngredients,
        List<String> excludedIngredients,
        List<String> requiredTools,
        Integer maxMinutes) {

    public RecipeQuery {
        text = text == null ? "" : text.trim();
        requiredIngredients = copy(requiredIngredients);
        excludedIngredients = copy(excludedIngredients);
        requiredTools = copy(requiredTools);
    }

    public String embeddingText() {
        StringBuilder value = new StringBuilder(text);
        append(value, "必须食材", requiredIngredients);
        append(value, "排除食材", excludedIngredients);
        append(value, "可用工具", requiredTools);
        if (maxMinutes != null) value.append("\n最长时间：").append(maxMinutes).append("分钟");
        return value.toString();
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
    }

    private static void append(StringBuilder target, String label, List<String> values) {
        if (!values.isEmpty()) target.append('\n').append(label).append('：').append(String.join("、", values));
    }
}
