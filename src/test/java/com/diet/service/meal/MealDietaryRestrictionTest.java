package com.diet.service.meal;

import com.diet.mapper.MealMapper;
import com.diet.model.MealRequest;
import com.diet.service.profile.DietaryProfileService;
import com.diet.service.slot.SlotOptionService;
import com.diet.util.JsonService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealDietaryRestrictionTest {
    @Test void rejectsSpicyAndKnownAllergenConflictsBeforeWriting() {
        var mapper=mock(MealMapper.class);
        var slots=mock(SlotOptionService.class);
        var profile=mock(DietaryProfileService.class);
        when(profile.get(7)).thenReturn(new DietaryProfileService.Profile(
                List.of("花生"),List.of(),List.of(),"NEVER","COMPLETED",""));
        var meals=new MealService(mapper,slots,mock(JsonService.class));
        meals.setDietaryProfileService(profile);
        assertThatThrownBy(() -> meals.createPersonalMeal(7L,new MealRequest("麻辣烫",List.of("午餐"),
                List.of(),List.of(),List.of(),List.of(),List.of("微辣"),List.of())))
                .hasMessageContaining("不吃辣");
        assertThatThrownBy(() -> meals.createPersonalMeal(7L,new MealRequest("花生面",List.of("午餐"),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of())))
                .hasMessageContaining("过敏原");
        verify(mapper,never()).insert(any());
    }
}
