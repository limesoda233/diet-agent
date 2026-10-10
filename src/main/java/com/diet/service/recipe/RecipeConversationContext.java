package com.diet.service.recipe;

import com.diet.model.MealResponse;
import java.util.List;

/** Latest displayed choices are distinct from cumulative recommendation exclusions. */
public record RecipeConversationContext(List<Dish> displayedMeals, String activeDish,
                                        boolean pendingSelection) {
    public RecipeConversationContext {
        displayedMeals = displayedMeals == null ? List.of() : List.copyOf(displayedMeals);
        activeDish = activeDish == null ? "" : activeDish.trim();
    }
    public static RecipeConversationContext empty() {
        return new RecipeConversationContext(List.of(), "", false);
    }
    public static RecipeConversationContext displayed(List<MealResponse> meals) {
        return new RecipeConversationContext(meals == null ? List.of() : meals.stream()
                .filter(meal -> meal != null && meal.name() != null && !meal.name().isBlank())
                .map(meal -> new Dish(meal.id(), meal.name())).toList(), "", false);
    }
    public RecipeConversationContext selected(String name) {
        return new RecipeConversationContext(displayedMeals, name, false);
    }
    public RecipeConversationContext pending() {
        return new RecipeConversationContext(displayedMeals, activeDish, true);
    }
    public record Dish(Long mealId, String name) { }
}
