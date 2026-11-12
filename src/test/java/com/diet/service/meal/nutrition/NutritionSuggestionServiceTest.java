package com.diet.service.meal.nutrition;

import com.diet.service.meal.intake.MealDraftModel;
import com.diet.service.profile.DietaryProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NutritionSuggestionServiceTest {
    @Test void modelCanPrefillButCannotOverrideUsersNoSpiceOrSesameAllergy() {
        var model=mock(MealDraftModel.class);
        when(model.extract(anyString(),anyString())).thenReturn(Mono.just("{\"oil\":\"HEAVY\",\"spice\":\"HOT\",\"sesame\":\"NORMAL\"}"));
        var profiles=mock(DietaryProfileService.class);
        when(profiles.get(7)).thenReturn(new DietaryProfileService.Profile(List.of("芝麻"),List.of(),List.of(),"NEVER","COMPLETED",""));
        var service=new NutritionSuggestionService(model,new ObjectMapper(),new NutritionTemplateService(),profiles);
        var draft=service.suggest(7,"麻辣烫","麻辣烫，不加辣");
        assertThat(draft.selections()).containsEntry("oil","HEAVY").containsEntry("spice","NONE")
                .containsEntry("sesame","NONE");
    }
    @Test void modelFailureStillReturnsReviewableChoices() {
        var model=mock(MealDraftModel.class);
        when(model.extract(anyString(),anyString())).thenReturn(Mono.error(new RuntimeException("offline")));
        var service=new NutritionSuggestionService(model,new ObjectMapper(),new NutritionTemplateService(),null);
        assertThat(service.suggest(7,"炸串","炸串").questions()).isNotEmpty();
    }
}
