package com.diet.service.meal.constraint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class IngredientCatalog {
    private final Set<String> ingredients;
    private final Map<String, String> aliases;
    private final Map<String, List<String>> slots;

    public IngredientCatalog() {
        try (var input = getClass().getResourceAsStream("/diet/retrieval/catalog.json")) {
            Catalog catalog = new ObjectMapper().readValue(input, Catalog.class);
            ingredients = Set.copyOf(catalog.ingredients());
            aliases = Map.copyOf(catalog.aliases());
            slots = Map.copyOf(catalog.slots());
        } catch (Exception error) {
            throw new IllegalStateException("无法加载规范食材词表", error);
        }
    }

    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("食材名称不能为空");
        }
        String trimmed = value.trim();
        String canonical = aliases.getOrDefault(trimmed, trimmed);
        if (!ingredients.contains(canonical)) {
            throw new IllegalArgumentException("无法识别食材“" + value + "”，请使用规范食材名称");
        }
        return canonical;
    }

    public void validate(MealConstraints constraints) {
        constraints.requiredIngredients().forEach(this::normalize);
        constraints.excludedIngredients().forEach(this::normalize);
        for (Map<String, List<String>> valuesBySlot :
                List.of(constraints.requiredSlots(), constraints.excludedSlots())) {
            valuesBySlot.forEach((key, values) -> {
                if (!slots.getOrDefault(key, List.of()).containsAll(values)) {
                    throw new IllegalArgumentException("未知标签限制: " + key);
                }
            });
        }
    }

    private record Catalog(List<String> ingredients,
                           Map<String, String> aliases,
                           Map<String, List<String>> slots) {
    }
}
