package com.diet.controller.meal;

import com.diet.auth.AuthPrincipal;
import com.diet.service.meal.nutrition.MealNutritionVariantService;
import com.diet.service.meal.nutrition.NutritionSuggestionService;
import com.diet.service.meal.nutrition.NutritionTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diet/meals")
public class MealNutritionController {
    private final MealNutritionVariantService variants;
    private final NutritionSuggestionService suggestions;
    private final NutritionTemplateService templates;
    public MealNutritionController(MealNutritionVariantService variants,NutritionSuggestionService suggestions,
                                   NutritionTemplateService templates) {
        this.variants=variants;this.suggestions=suggestions;this.templates=templates;
    }
    @PostMapping("/nutrition/suggest")
    public NutritionTemplateService.Draft suggest(@AuthenticationPrincipal AuthPrincipal user,@RequestBody SuggestRequest request) {
        return suggestions.suggest(user.userId(),request.name(),request.text());
    }
    @PostMapping("/nutrition/preview")
    public NutritionTemplateService.Estimate preview(@RequestBody PreviewRequest request) {
        return templates.estimate(request.templateCode(),request.selections());
    }
    @GetMapping("/personal/{mealId}/variants")
    public List<MealNutritionVariantService.Variant> personal(@AuthenticationPrincipal AuthPrincipal user,@PathVariable long mealId) {
        return variants.list(user.userId(),mealId);
    }
    @GetMapping("/public/{mealId}/variants")
    public List<MealNutritionVariantService.Variant> publicVariants(@AuthenticationPrincipal AuthPrincipal user,@PathVariable long mealId) {
        return variants.list(user.userId(),mealId);
    }
    @PostMapping("/personal/{mealId}/variants")
    public MealNutritionVariantService.Variant save(@AuthenticationPrincipal AuthPrincipal user,@PathVariable long mealId,
                                                     @RequestBody MealNutritionVariantService.Input input) {
        return variants.savePersonal(user.userId(),mealId,input);
    }
    @DeleteMapping("/personal/{mealId}/variants/{variantId}")
    public void delete(@AuthenticationPrincipal AuthPrincipal user,@PathVariable long mealId,@PathVariable long variantId) {
        variants.deletePersonal(user.userId(),mealId,variantId);
    }
    public record SuggestRequest(String name,String text) {}
    public record PreviewRequest(String templateCode,java.util.Map<String,String> selections) {}
}
