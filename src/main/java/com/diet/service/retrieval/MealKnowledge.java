package com.diet.service.retrieval;

import java.util.List;
import java.util.Set;

public record MealKnowledge(List<String> ingredients, String description, String preparation,
                            String sourceType, String sourceReference) {
    public MealKnowledge {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        description = bounded(description, 2000); preparation = bounded(preparation, 4000);
        sourceReference = bounded(sourceReference, 1000);
        if (sourceType == null || !Set.of("USER_PROVIDED", "IMPORTED", "SYNTHETIC_EVALUATION").contains(sourceType))
            throw new IllegalArgumentException("知识来源类型无效");
        if (ingredients.size() > 64 || ingredients.stream().anyMatch(x -> x == null || x.isBlank() || !x.equals(x.trim()) || x.length() > 64))
            throw new IllegalArgumentException("食材字段无效");
    }
    public boolean ingredientDataPresent() { return !ingredients.isEmpty(); }
    private static String bounded(String value, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) throw new IllegalArgumentException("知识字段过长");
        return text;
    }
}
