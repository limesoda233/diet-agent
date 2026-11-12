package com.diet.service.meal.nutrition;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class NutritionTemplateServiceTest {
    private final NutritionTemplateService templates = new NutritionTemplateService();

    @Test void explicitNoSpiceAndSesameOverrideModelSuggestions() {
        var draft = templates.draft("麻辣烫", "麻辣烫，不加辣，不要麻酱", Map.of("spice", "HOT", "sesame", "NORMAL"));
        assertThat(draft.templateCode()).isEqualTo("MALATANG");
        assertThat(draft.selections()).containsEntry("spice", "NONE").containsEntry("sesame", "NONE");
        assertThat(draft.questions()).extracting(NutritionTemplateService.Question::key)
                .contains("size", "starch", "oil", "sesame", "sugar", "spice");
    }

    @Test void largerPortionAndMoreOilIncreaseEstimatedRange() {
        var base = templates.draft("麻辣烫", "麻辣烫", Map.of());
        var small = templates.estimate("MALATANG", base.selections());
        var large = templates.estimate("MALATANG", Map.of("size", "LARGE", "composition", "BALANCED",
                "starch", "REGULAR", "oil", "HEAVY", "sesame", "NORMAL", "sugar", "NONE", "spice", "MILD"));
        assertThat(large.kcalLow()).isGreaterThan(small.kcalLow());
        assertThat(large.carbsHigh()).isGreaterThan(small.carbsHigh());
        assertThat(large.assumptions()).isNotEmpty();
    }

    @Test void unsupportedDishHasNoInventedCalories() {
        var draft = templates.draft("神秘拼盘", "神秘拼盘", Map.of());
        assertThat(draft.estimate()).isNull();
        assertThat(draft.questions()).isEmpty();
    }
}
