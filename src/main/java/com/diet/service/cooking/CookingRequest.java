package com.diet.service.cooking;
import java.util.List;

public record CookingRequest(String action,String recipeId,String query,String ingredient,
                             List<String> availableIngredients,List<String> requiredIngredients,List<String> excludedIngredients,int limit) {}
