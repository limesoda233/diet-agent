package com.diet.service.meal.constraint;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record MealConstraints(List<String> requiredIngredients,
                              List<String> excludedIngredients,
                              Map<String, List<String>> requiredSlots,
                              Map<String, List<String>> excludedSlots) {
    private static final Set<String> SLOT_NAMES = Set.of(
            "mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience");

    public MealConstraints {
        requiredIngredients = clean(requiredIngredients);
        excludedIngredients = clean(excludedIngredients);
        requiredSlots = cleanMap(requiredSlots);
        excludedSlots = cleanMap(excludedSlots);
        if (!Collections.disjoint(requiredIngredients, excludedIngredients)) {
            throw new IllegalArgumentException("同一食材不能既必须包含又排除，请澄清限制");
        }
        for (Map.Entry<String, List<String>> entry : requiredSlots.entrySet()) {
            if (!entry.getValue().isEmpty()
                    && excludedSlots.getOrDefault(entry.getKey(), List.of()).containsAll(entry.getValue())) {
                throw new IllegalArgumentException("标签的包含与排除条件冲突");
            }
        }
    }

    public static MealConstraints empty() {
        return new MealConstraints(null, null, null, null);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return requiredIngredients.isEmpty() && excludedIngredients.isEmpty()
                && requiredSlots.isEmpty() && excludedSlots.isEmpty();
    }

    private static List<String> clean(List<String> input) {
        if (input == null) {
            return List.of();
        }
        if (input.size() > 64 || input.stream().anyMatch(value ->
                value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 64)) {
            throw new IllegalArgumentException("限制必须使用有效的规范名称");
        }
        return List.copyOf(new LinkedHashSet<>(input));
    }

    private static Map<String, List<String>> cleanMap(Map<String, List<String>> input) {
        if (input == null) {
            return Map.of();
        }
        Map<String, List<String>> result = new TreeMap<>();
        input.forEach((key, values) -> {
            if (!SLOT_NAMES.contains(key)) {
                throw new IllegalArgumentException("未知限制维度: " + key);
            }
            List<String> normalized = clean(values);
            if (!normalized.isEmpty()) {
                result.put(key, normalized);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
