package com.diet.service.retrieval;

import com.diet.service.meal.constraint.MealConstraints;

import com.diet.service.meal.knowledge.MealKnowledge;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealDocumentTest {
    static MealDocument document(long id, SourceMode mode, Long owner, List<String> ingredients) {
        var slots = new SlotBundle(List.of("晚餐"), null, null, null, null, List.of("清淡"), null);
        var knowledge = ingredients == null ? null : new MealKnowledge(ingredients, "家常菜", "煮熟", "USER_PROVIDED", "");
        return new MealDocument(new MealItem(id, mode, owner, "餐食" + id, slots, 0), knowledge);
    }
    @Test void rechecksScopeExclusionsAndUnknownIngredients() {
        var constraints = new MealConstraints(List.of("鸡蛋"), List.of("牛肉"), null, null);
        var request = new MealRetrievalRequest("晚餐", SourceMode.PERSONAL, 1L, SlotBundle.empty(), List.of(4L), constraints);
        assertThat(document(1, SourceMode.PERSONAL, 1L, List.of("鸡蛋")).violations(request)).isEmpty();
        assertThat(document(2, SourceMode.PERSONAL, 2L, List.of("鸡蛋")).violations(request)).contains("sourceScope");
        assertThat(document(3, SourceMode.PERSONAL, 1L, null).violations(request)).contains("ingredientDataMissing");
        assertThat(document(4, SourceMode.PERSONAL, 1L, List.of("鸡蛋", "牛肉")).violations(request)).contains("excludedMealId", "excludedIngredient");
    }
    @Test void hashesAllRetrievalEvidenceAndExcludesMutableRankingScore() {
        var first = document(1, SourceMode.PUBLIC, null, List.of("鸡蛋"));
        String original = first.contentHash();
        first.meal().matchScore(0.9);
        assertThat(first.contentHash()).isEqualTo(original);
        assertThat(document(1, SourceMode.PUBLIC, null, List.of("牛肉")).contentHash()).isNotEqualTo(original);
        assertThat(first.text()).contains("鸡蛋", "家常菜", "USER_PROVIDED");
    }
}
