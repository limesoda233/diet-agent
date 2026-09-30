package com.diet.service.meal;

import com.diet.enums.SourceMode;
import com.diet.model.MealSearchRequest;
import com.diet.model.SlotBundle;
import com.diet.service.meal.constraint.MealConstraints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MealConstraintSearchTest {

    @Test
    void forwardsExplicitIngredientConstraintsToMysqlSearch() {
        MealService mealService = mock(MealService.class);
        MealSearchService searchService = new MealSearchService(mealService);
        SlotBundle slots = SlotBundle.empty();
        MealConstraints constraints = new MealConstraints(
                List.of("番茄"), List.of("牛肉"), Map.of(), Map.of());
        MealSearchRequest request = new MealSearchRequest(
                SourceMode.PUBLIC, 1L, slots, List.of(), constraints);

        searchService.search(request);

        verify(mealService).search(SourceMode.PUBLIC, 1L, slots, constraints);
    }
}
