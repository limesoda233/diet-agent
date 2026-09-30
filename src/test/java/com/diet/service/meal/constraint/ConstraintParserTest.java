package com.diet.service.meal.constraint;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ConstraintParserTest {
    private final ConstraintParser parser = new ConstraintParser(new IngredientCatalog());

    @Test void extractsExplicitIngredientsAndAliasesWithoutMakingPantryAHardRequirement() {
        assertThat(parser.resolve("晚饭不要牛肉", MealConstraints.empty(), null).excludedIngredients()).containsExactly("牛肉");
        assertThat(parser.resolve("同时用西红柿和鸡蛋", MealConstraints.empty(), null).requiredIngredients()).containsExactly("番茄", "鸡蛋");
        assertThat(parser.resolve("家里有鸡蛋", MealConstraints.empty(), null).requiredIngredients()).isEmpty();
        assertThat(parser.resolve("不要辣", MealConstraints.empty(), null).excludedSlots().get("taste")).contains("微辣", "麻辣");
    }

    @Test void preservesRestrictionsUntilExplicitlyRemovedOrReplaced() {
        var previous = parser.resolve("不吃牛肉", MealConstraints.empty(), null);
        assertThat(parser.resolve("换一批", previous, null)).isEqualTo(previous);
        assertThat(parser.resolve("取消牛肉限制", previous, null).excludedIngredients()).isEmpty();
        assertThat(parser.resolve("换一批", previous, Map.of("retrievalConstraints", Map.of())).isEmpty()).isTrue();
        assertThat(parser.resolve("午餐", previous, Map.of("retrievalConstraints", Map.of("requiredIngredients", List.of("鸡蛋")))).requiredIngredients()).containsExactly("鸡蛋");
    }

    @Test void rejectsUnknownConflictingAmbiguousOrMalformedRestrictions() {
        for (String text : List.of("不要不放牛肉", "不要牛肉和神秘食材", "必须含火星果", "牛肉必须用上但不要牛肉", "必须是川菜", "只要川菜", "不能吃牛肉", "必须用鸡蛋或豆腐", "不要取消所有限制")) {
            assertThatThrownBy(() -> parser.resolve(text, MealConstraints.empty(), null)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> parser.resolve("午餐", MealConstraints.empty(), Map.of("retrievalConstraints", Map.of("requiredIngredients", "鸡蛋")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.resolve("午餐", MealConstraints.empty(), Map.of("retrievalConstraints", Map.of("unknown", true)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.resolve("午餐", MealConstraints.empty(), Map.of("retrievalConstraints", Map.of("requiredIngredients", List.of("鸡蛋"), "excludedIngredients", List.of("鸡蛋"))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void clearsOldRestrictionsThenAppliesNewRestrictionsInTheSameTurn() {
        var previous = parser.resolve("不吃鸡蛋", MealConstraints.empty(), null);
        assertThat(parser.resolve("取消所有限制，不吃牛肉", previous, null).excludedIngredients()).containsExactly("牛肉");
    }

    @Test void rejectsExplicitNullInStructuredRestrictions() {
        var nullField = new java.util.HashMap<String,Object>(); nullField.put("excludedIngredients", null);
        assertThatThrownBy(() -> parser.resolve("换一批", MealConstraints.empty(), Map.of("retrievalConstraints", nullField))).isInstanceOf(IllegalArgumentException.class);
        var nullSlots = new java.util.HashMap<String,Object>(); nullSlots.put("taste", null);
        assertThatThrownBy(() -> parser.resolve("换一批", MealConstraints.empty(), Map.of("retrievalConstraints", Map.of("excludedSlots", nullSlots)))).isInstanceOf(IllegalArgumentException.class);
    }
}
