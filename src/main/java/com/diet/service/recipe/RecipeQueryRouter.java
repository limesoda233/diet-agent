package com.diet.service.recipe;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeAnswerPurpose;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class RecipeQueryRouter {
    private static final Pattern RECIPE_WORDS = Pattern.compile(
            "(怎么做|咋做|如何做|怎么弄|咋弄|做法|菜谱|食谱|烹饪步骤|制作步骤|需要什么食材|要哪些食材)");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "mealId", "query", "requiredIngredients", "excludedIngredients", "requiredTools", "maxMinutes");

    public boolean matches(String message, Map<String, Object> context) {
        return contextMap(context).containsKey("recipeQuery")
                || RecipeAnswerPurpose.fromText(message) == RecipeAnswerPurpose.INGREDIENT_SUGGESTION
                || (message != null && RECIPE_WORDS.matcher(message).find());
    }

    public Optional<RoutedRecipeQuery> route(String message, Map<String, Object> context) {
        Map<String, Object> safeContext = contextMap(context);
        Object structured = safeContext.get("recipeQuery");
        if (structured == null) {
            if (!matches(message, safeContext)) return Optional.empty();
            return Optional.of(new RoutedRecipeQuery(null,
                    new RecipeQuery(message, List.of(), List.of(), List.of(), null)));
        }
        if (!(structured instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("recipeQuery 必须是对象");
        }
        Set<String> unknown = new LinkedHashSet<>();
        raw.keySet().forEach(key -> {
            if (!(key instanceof String name) || !ALLOWED_FIELDS.contains(name)) unknown.add(String.valueOf(key));
        });
        if (!unknown.isEmpty()) throw new IllegalArgumentException("recipeQuery 包含未知字段: " + String.join(", ", unknown));
        Long mealId = longValue(raw.get("mealId"));
        String query = stringValue(raw.get("query"));
        if (query.isBlank()) query = message == null ? "" : message.trim();
        return Optional.of(new RoutedRecipeQuery(mealId, new RecipeQuery(query,
                stringList(raw.get("requiredIngredients"), "requiredIngredients"),
                stringList(raw.get("excludedIngredients"), "excludedIngredients"),
                stringList(raw.get("requiredTools"), "requiredTools"), minuteLimit(raw.get("maxMinutes")))));
    }

    private Map<String, Object> contextMap(Map<String, Object> context) {
        return context == null ? Map.of() : context;
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                throw new IllegalArgumentException("mealId 必须是整数");
            }
        }
        throw new IllegalArgumentException("mealId 必须是整数");
    }

    private Integer minuteLimit(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            try {
                int minutes = new BigDecimal(number.toString()).intValueExact();
                if (minutes > 0) return minutes;
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Fractional, non-finite and overflowing values are not valid limits.
            }
        }
        throw new IllegalArgumentException("maxMinutes 必须是正整数");
    }

    private String stringValue(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text.trim();
        throw new IllegalArgumentException("query 必须是字符串");
    }

    private List<String> stringList(Object value, String field) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values) || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException(field + " 必须是字符串数组");
        }
        return values.stream().map(String.class::cast).map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    public record RoutedRecipeQuery(Long mealId, RecipeQuery query) {
    }
}
