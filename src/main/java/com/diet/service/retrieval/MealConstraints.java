package com.diet.service.retrieval;

import java.util.*;

public record MealConstraints(List<String> requiredIngredients, List<String> excludedIngredients,
                              Map<String, List<String>> requiredSlots, Map<String, List<String>> excludedSlots) {
    public MealConstraints {
        requiredIngredients = clean(requiredIngredients);
        excludedIngredients = clean(excludedIngredients);
        requiredSlots = cleanMap(requiredSlots);
        excludedSlots = cleanMap(excludedSlots);
        if (!Collections.disjoint(requiredIngredients, excludedIngredients))
            throw new IllegalArgumentException("同一食材不能既必须包含又排除，请澄清限制");
        for (var entry : requiredSlots.entrySet()) {
            if (excludedSlots.getOrDefault(entry.getKey(), List.of()).containsAll(entry.getValue()) && !entry.getValue().isEmpty())
                throw new IllegalArgumentException("标签的包含与排除条件冲突");
        }
    }
    public static MealConstraints empty() { return new MealConstraints(null, null, null, null); }
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() { return requiredIngredients.isEmpty() && excludedIngredients.isEmpty() && requiredSlots.isEmpty() && excludedSlots.isEmpty(); }
    private static List<String> clean(List<String> input) {
        if (input == null) return List.of();
        if (input.size() > 64 || input.stream().anyMatch(s -> s == null || s.isBlank() || !s.equals(s.trim()) || s.length() > 64))
            throw new IllegalArgumentException("限制必须使用有效的规范名称");
        return List.copyOf(new LinkedHashSet<>(input));
    }
    private static Map<String, List<String>> cleanMap(Map<String, List<String>> input) {
        if (input == null) return Map.of();
        var result = new TreeMap<String, List<String>>();
        input.forEach((key, values) -> {
            if (!RetrievalSlots.NAMES.contains(key)) throw new IllegalArgumentException("未知限制维度: " + key);
            var normalized = clean(values);
            if (!normalized.isEmpty()) result.put(key, normalized);
        });
        return Collections.unmodifiableMap(result);
    }
}
