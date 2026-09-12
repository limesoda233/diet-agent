package com.diet.service.cooking;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RecipeProviderTest {
    @Test void enforcesRestrictionsAndReportsMissingSeasonings() {
        RecipeProvider provider=new LocalRecipeProvider();
        var result=provider.call("search_recipes",Map.of("availableIngredients",List.of("西红柿","鸡蛋"),"requiredIngredients",List.of("番茄","鸡蛋")));
        var recipes=(List<Map<String,Object>>)result.get("recipes");
        assertThat(recipes).isNotEmpty();
        assertThat((List<String>)recipes.get(0).get("missingIngredients")).contains("盐");
        assertThat(provider.call("search_recipes",Map.of("requiredIngredients",List.of("牛肉"),"excludedIngredients",List.of("牛肉"))).get("recipes")).asList().isEmpty();
        assertThatThrownBy(()->provider.call("get_recipe",Map.of("recipeId","unknown"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.call("get_recipe",Map.of("recipeId","recipe_01","excludedIngredients",List.of("鸡蛋")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.call("search_recipes",Map.of("limit","3"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->provider.call("search_recipes",Map.of("secret",1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void substitutionsAreScopedDirectionalAndCheckTheWholeResult() {
        RecipeProvider provider=new LocalRecipeProvider();
        var result=provider.call("suggest_substitutions",Map.of("recipeId","recipe_03","ingredient","香菇"));
        assertThat(result.get("substitutions")).asList().isNotEmpty();
        assertThat(provider.call("suggest_substitutions",Map.of("recipeId","recipe_03","ingredient","香菇","excludedIngredients",List.of("豆腐"))).get("substitutions")).asList().isEmpty();
        assertThat(provider.call("suggest_substitutions",Map.of("recipeId","recipe_01","ingredient","鸡蛋")).get("substitutions")).asList().isEmpty();
    }
}
