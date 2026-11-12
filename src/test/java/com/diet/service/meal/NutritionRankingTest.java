package com.diet.service.meal;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.meal.nutrition.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class NutritionRankingTest {
    @Test void broadSpicyRequestRanksMildAsMatchButNotNonSpicy() {
        var rank=new MealRankService();
        var query=new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of(),List.of(),List.of("辣"),List.of());
        var mild=new MealItem(1L,SourceMode.PERSONAL,7L,"微辣麻辣烫",
                new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of(),List.of(),List.of("微辣"),List.of()),0);
        var plain=new MealItem(2L,SourceMode.PERSONAL,7L,"不辣麻辣烫",
                new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of(),List.of(),List.of("不辣"),List.of()),0);
        var result=rank.rank(new MealRankRequest(List.of(plain,mild),query,List.of()));
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).matchScore()).isGreaterThan(result.get(1).matchScore());
    }
    @Test void nutritionGoalDoesNotRequireAnOldHealthTagToRecallMeal() {
        var meals=mock(MealService.class);
        var search=new MealSearchService(meals);
        var slots=new SlotBundle(List.of("午餐"),List.of(),List.of(),List.of("控碳水"),List.of(),List.of(),List.of());
        search.search(new MealSearchRequest(SourceMode.PUBLIC,7L,slots,List.of()));
        var captured=ArgumentCaptor.forClass(SlotBundle.class);
        verify(meals).search(eq(SourceMode.PUBLIC),eq(7L),captured.capture(),any());
        assertThat(captured.getValue().healthGoal()).isEmpty();
        assertThat(captured.getValue().mealTime()).containsExactly("午餐");
    }
    @Test void lowCarbRequestPrefersVersionWithLessEstimatedCarbs() {
        var nutrition=mock(MealNutritionVariantService.class);
        var low=new NutritionTemplateService.Estimate(300,480,12,25,20,35,10,20,3,7,"APPROX_RULE_V1",List.of("估算"));
        var high=new NutritionTemplateService.Estimate(450,750,45,80,20,35,20,40,3,7,"APPROX_RULE_V1",List.of("估算"));
        when(nutrition.bestInternal(1,"控碳水")).thenReturn(new MealNutritionVariantService.Variant(10,1,"少主食","MALATANG",Map.of(),low));
        when(nutrition.bestInternal(2,"控碳水")).thenReturn(new MealNutritionVariantService.Variant(11,2,"多主食","MALATANG",Map.of(),high));
        var rank=new MealRankService();
        rank.setNutritionVariants(nutrition);
        var query=new SlotBundle(List.of("午餐"),List.of(),List.of(),List.of("控碳水"),List.of(),List.of(),List.of());
        var items=List.of(new MealItem(2L,SourceMode.PUBLIC,null,"多主食",query,0),
                new MealItem(1L,SourceMode.PUBLIC,null,"少主食",query,0));
        var result=rank.rank(new MealRankRequest(items,query,List.of()));
        assertThat(result).extracting(MealItem::id).startsWith(1L);
    }
}
