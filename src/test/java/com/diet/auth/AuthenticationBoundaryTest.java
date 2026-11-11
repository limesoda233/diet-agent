package com.diet.auth;

import com.diet.controller.meal.MealController;
import com.diet.service.meal.MealService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationBoundaryTest {
    @Test
    void missingBearerCannotReachMealService() throws Exception {
        AuthService auth = mock(AuthService.class);
        MealService meals = mock(MealService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MealController(meals))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new BearerAuthenticationFilter(auth)).build();

        mvc.perform(get("/api/v1/diet/meals/personal").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(meals);
    }

    @Test
    void forgedUserHeaderCannotOverrideAuthenticatedUser() throws Exception {
        AuthService auth = mock(AuthService.class);
        MealService meals = mock(MealService.class);
        when(auth.authenticate("valid")).thenReturn(new AuthPrincipal(7L, "USER", "session-7"));
        when(meals.findPersonalMeals(7L)).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new MealController(meals))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new BearerAuthenticationFilter(auth)).build();

        mvc.perform(get("/api/v1/diet/meals/personal")
                        .header("Authorization", "Bearer valid")
                        .header("X-User-Id", "1"))
                .andExpect(status().isOk());
        verify(meals).findPersonalMeals(7L);
        verify(meals, never()).findPersonalMeals(1L);
    }
}
