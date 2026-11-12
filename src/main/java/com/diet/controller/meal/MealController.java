package com.diet.controller.meal;

import com.diet.auth.AuthPrincipal;
import com.diet.model.MealRequest;
import com.diet.model.MealResponse;
import com.diet.service.meal.MealService;
import com.diet.model.MealItem;
import com.diet.service.meal.nutrition.MealNutritionVariantService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diet/meals")
public class MealController {
    private final MealService mealService;
    private MealNutritionVariantService nutritionVariants;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setNutritionVariants(MealNutritionVariantService service) { this.nutritionVariants=service; }

    public MealController(MealService mealService) {
        this.mealService = mealService;
    }

    @GetMapping("/personal")
    public List<MealResponse> findPersonal(@AuthenticationPrincipal AuthPrincipal principal) {
        return mealService.findPersonalMeals(principal.userId()).stream().map(this::response).toList();
    }

    @PostMapping("/personal")
    public MealResponse createPersonal(@AuthenticationPrincipal AuthPrincipal principal, @RequestBody MealRequest request) {
        return response(mealService.createPersonalMeal(principal.userId(), request));
    }
    @PostMapping("/personal/merge")
    public MealResponse mergePersonal(@AuthenticationPrincipal AuthPrincipal principal,@RequestBody MealRequest request) {
        return response(mealService.mergePersonalMeal(principal.userId(),request));
    }

    @PutMapping("/personal/{mealId}")
    public MealResponse updatePersonal(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long mealId, @RequestBody MealRequest request) {
        return response(mealService.updatePersonalMeal(principal.userId(), mealId, request));
    }

    @DeleteMapping("/personal/{mealId}")
    public void deletePersonal(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable Long mealId) {
        mealService.deletePersonalMeal(principal.userId(), mealId);
    }

    @GetMapping("/public")
    public List<MealResponse> findPublic() {
        return mealService.findPublicMeals().stream().map(this::response).toList();
    }
    private MealResponse response(MealItem item) {
        var result=MealResponse.from(item);
        if(nutritionVariants!=null) result.nutritionVariant(nutritionVariants.bestInternal(item.id(),null));
        return result;
    }
}
