package com.diet.service.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeQueryRouterTest {

    @Test
    void recognizesColloquialRecipeQuestion() {
        RecipeQueryRouter router = new RecipeQueryRouter();

        assertThat(router.matches("宫保鸡丁咋做啊", Map.of())).isTrue();
        assertThat(router.route("宫保鸡丁咋做啊", Map.of())).isPresent();
    }

    @Test
    void preservesToolsAndMinuteLimitFromStructuredRecipeContext() {
        var query = new RecipeQueryRouter().route("原问题", Map.of("recipeQuery", Map.of(
                "query", "炒饭怎么做", "requiredIngredients", List.of("米饭"),
                "excludedIngredients", List.of("花生"), "requiredTools", List.of("炒锅"), "maxMinutes", 25L)))
                .orElseThrow().query();
        assertThat(query.text()).isEqualTo("炒饭怎么做");
        assertThat(query.requiredIngredients()).containsExactly("米饭");
        assertThat(query.excludedIngredients()).containsExactly("花生");
        assertThat(query.requiredTools()).containsExactly("炒锅");
        assertThat(query.maxMinutes()).isEqualTo(25);
    }

    @Test
    void leavesAbsentToolsAndMinuteLimitUnconstrained() {
        var query = new RecipeQueryRouter().route("做法", Map.of("recipeQuery", Map.of("query", "炒饭")))
                .orElseThrow().query();
        assertThat(query.requiredTools()).isEmpty();
        assertThat(query.maxMinutes()).isNull();
    }

    @Test
    void stillRejectsUnknownStructuredFields() {
        assertThatThrownBy(() -> new RecipeQueryRouter().route("做法", Map.of("recipeQuery", Map.of(
                "query", "炒饭", "requiredTools", List.of("炒锅"), "maxMinutes", 25, "override", true))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("override");
    }

    @Test
    void rejectsMalformedToolsAndNonPositiveOrNonIntegralMinuteLimits() {
        assertThatThrownBy(() -> new RecipeQueryRouter().route("做法", Map.of("recipeQuery", Map.of(
                "query", "炒饭", "requiredTools", "炒锅"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requiredTools");
        for (Object invalid : List.of(0, -1, 1.5, "25", 2147483648L)) {
            assertThatThrownBy(() -> new RecipeQueryRouter().route("做法", Map.of("recipeQuery", Map.of(
                    "query", "炒饭", "maxMinutes", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxMinutes");
        }
    }
}
