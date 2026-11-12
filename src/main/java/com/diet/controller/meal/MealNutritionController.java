package com.diet.controller.meal;

import com.diet.auth.AuthPrincipal;
import com.diet.service.meal.nutrition.MealNutritionVariantService;
import com.diet.service.meal.nutrition.NutritionSuggestionService;
import com.diet.service.meal.nutrition.NutritionTemplateService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diet/meals")
public class MealNutritionController {
    private static final Logger log=LoggerFactory.getLogger(MealNutritionController.class);
    private final MealNutritionVariantService variants;
    private final NutritionSuggestionService suggestions;
    private final NutritionTemplateService templates;
    public MealNutritionController(MealNutritionVariantService variants,NutritionSuggestionService suggestions,
                                   NutritionTemplateService templates) {
        this.variants=variants;this.suggestions=suggestions;this.templates=templates;
    }
    @PostMapping("/nutrition/suggest")
    public NutritionTemplateService.Draft suggest(@AuthenticationPrincipal AuthPrincipal user,@RequestBody SuggestRequest request) {
        String requestId=java.util.UUID.randomUUID().toString();
        long started=System.nanoTime();
        try {
            var draft=suggestions.suggest(user.userId(),request.name(),request.text());
            log.info("DIET_DIAG v=1 event=NUTRITION_SUGGEST requestId={} template={} questions={} durationMs={}",
                    requestId,draft.templateCode(),draft.questions().size(),(System.nanoTime()-started)/1_000_000);
            return draft;
        } catch(RuntimeException error) {
            log.warn("DIET_DIAG v=1 event=NUTRITION_SUGGEST_FAILED requestId={} errorType={} durationMs={}",
                    requestId,error.getClass().getSimpleName(),(System.nanoTime()-started)/1_000_000);
            throw error;
        }
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
