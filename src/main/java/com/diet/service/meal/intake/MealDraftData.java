package com.diet.service.meal.intake;

import com.diet.model.MealRequest;
import com.diet.service.meal.knowledge.MealKnowledge;
import java.util.List;

public record MealDraftData(String name, List<String> mealTime, List<String> mood,
        List<String> scene, List<String> healthGoal, List<String> cuisine,
        List<String> taste, List<String> convenience, MealKnowledge knowledge) {
    public MealDraftData {
        name = name == null ? "" : name;
        mealTime = copy(mealTime); mood = copy(mood); scene = copy(scene);
        healthGoal = copy(healthGoal); cuisine = copy(cuisine);
        taste = copy(taste); convenience = copy(convenience);
    }
    private static List<String> copy(List<String> values) { return values == null ? List.of() : List.copyOf(values); }
    public MealRequest toMealRequest() {
        var request = new MealRequest(name,mealTime,mood,scene,healthGoal,cuisine,taste,convenience);
        if (knowledge != null) request.knowledge(knowledge);
        return request;
    }
}
