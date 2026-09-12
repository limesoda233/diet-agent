package com.diet.service.cooking;

import com.diet.service.retrieval.MealConstraints;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CookingServiceTest {
    private final CookingProperties properties=new CookingProperties();
    private CookingService service(RecipeProvider provider) { properties.setEnabled(true); return new CookingService(provider,properties,mock(AgentTraceService.class)); }
    @Test void routesFiniteGrammarAndNeverRelaxesSessionRestrictions() {
        var router=new CookingRouter();
        assertThat(router.route("用番茄和鸡蛋找食谱",null).orElseThrow().action()).isEqualTo("search");
        assertThat(router.route("番茄炒蛋怎么做",null).orElseThrow().action()).isEqualTo("detail");
        assertThat(router.route("recipe_08里的牛奶可以用什么替换",null).orElseThrow().action()).isEqualTo("substitute");
        assertThat(router.route("晚餐吃什么",null)).isEmpty();
        var service=service(new LocalRecipeProvider());
        var request=router.route("",Map.of("cookingRequest",Map.of("action","detail","recipeId","recipe_01","excludedIngredients",List.of()))).orElseThrow();
        assertThatThrownBy(()->service.respond(request,new MealConstraints(null,List.of("鸡蛋"),null,null))).isInstanceOf(IllegalArgumentException.class);
        var search=router.route("用番茄和鸡蛋找食谱",null).orElseThrow();
        assertThat(service.respond(search,MealConstraints.empty())).contains("LOCAL_EXAMPLE","recipe_01","缺少","盐");
        var replacement=router.route("recipe_08里的牛奶可以用什么替换",null).orElseThrow();
        assertThat(service.respond(replacement,new MealConstraints(null,List.of("牛奶"),null,null))).contains("燕麦奶","替换比例未记录");
    }
    @Test void rejectsMalformedResultsAndReportsUnavailabilityWithoutInventingRecipes() {
        var request=new CookingRouter().route("番茄炒蛋怎么做",null).orElseThrow();
        var provider=mock(RecipeProvider.class);
        when(provider.call(any(),any())).thenReturn(Map.of("recipes",List.of(Map.of("recipeId","fake"))));
        assertThat(service(provider).respond(request,MealConstraints.empty())).contains("暂时不可用");
        when(provider.call(any(),any())).thenThrow(new IllegalStateException("offline"));
        assertThat(service(provider).respond(request,MealConstraints.empty())).contains("暂时不可用");
        assertThatThrownBy(()->new CookingRouter().route("",Map.of("cookingRequest",Map.of("action","detail","recipeId","recipe_01","userId",2))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsReplacementThatSilentlyDropsAnotherIngredient() {
        var local=new LocalRecipeProvider();
        RecipeProvider tampered=(tool,args)-> {
            var result=local.call(tool,args);
            if(!tool.equals("suggest_substitutions")) return result;
            @SuppressWarnings("unchecked") var rules=(List<Map<String,Object>>)result.get("substitutions");
            var rule=new HashMap<>(rules.get(0));
            rule.put("resultingIngredients",List.of("燕麦奶")); // Missing the original oats.
            return Map.of("substitutions",List.of(rule));
        };
        var request=new CookingRouter().route("recipe_08里的牛奶可以用什么替换",null).orElseThrow();
        assertThat(service(tampered).respond(request,MealConstraints.empty())).contains("暂时不可用").doesNotContain(" → ");
    }
    @Test void disabledFeatureDoesNotCallTools() {
        var provider=mock(RecipeProvider.class);
        var disabled=new CookingService(provider,new CookingProperties(),mock(AgentTraceService.class));
        assertThat(disabled.matches("番茄炒蛋怎么做",null)).isFalse();
        assertThat(disabled.matches("",Map.of("cookingRequest",Map.of("action","search")))).isTrue();
        assertThat(disabled.respond(new CookingRouter().route("番茄炒蛋怎么做",null).orElseThrow(),MealConstraints.empty())).contains("尚未启用");
        verifyNoInteractions(provider);
    }
}
