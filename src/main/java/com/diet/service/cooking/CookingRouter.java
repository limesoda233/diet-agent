package com.diet.service.cooking;

import com.diet.service.meal.constraint.IngredientCatalog;

import java.util.*;
import java.util.regex.Pattern;

/** Explicit grammar, independent from the existing six-intent LLM benchmark. */
public final class CookingRouter {
    private static final Pattern SEARCH=Pattern.compile("^用(.+?)(?:找|搜索)食谱[。？?]?$" );
    private static final Pattern DETAIL=Pattern.compile("^(.+?)(?:怎么做|的做法)[。？?]?$" );
    private static final Pattern SUBSTITUTE=Pattern.compile("^(recipe_[0-9]{2})里的(.+?)可以用什么替换[。？?]?$" );

    public boolean matches(String message,Map<String,Object> context) {
        return context!=null && context.containsKey("cookingRequest") || message!=null &&
                (SEARCH.matcher(message.trim()).matches() || DETAIL.matcher(message.trim()).matches() || SUBSTITUTE.matcher(message.trim()).matches());
    }
    public Optional<CookingRequest> route(String message,Map<String,Object> context) {
        if(context!=null && context.containsKey("cookingRequest")) {
            if(!(context.get("cookingRequest") instanceof Map<?,?> raw) || raw.keySet().stream().anyMatch(k->!(k instanceof String)))
                throw new IllegalArgumentException("cookingRequest 必须是对象");
            @SuppressWarnings("unchecked") var args=(Map<String,Object>)raw;
            return Optional.of(structured(args));
        }
        String text=message==null?"":message.trim();
        var substitute=SUBSTITUTE.matcher(text);
        if(substitute.matches()) return Optional.of(structured(Map.of("action","substitute","recipeId",substitute.group(1),"ingredient",substitute.group(2))));
        var search=SEARCH.matcher(text);
        if(search.matches()) return Optional.of(structured(Map.of("action","search","availableIngredients",Arrays.asList(search.group(1).split("和|、|与|加",-1)))));
        var detail=DETAIL.matcher(text);
        if(detail.matches()) {
            String target=detail.group(1).trim();
            return Optional.of(structured(Map.of("action","detail",target.matches("recipe_[0-9]{2}")?"recipeId":"query",target)));
        }
        return Optional.empty();
    }
    private CookingRequest structured(Map<String,Object> args) {
        CookingArguments.fields(args,Set.of("action","recipeId","query","ingredient","availableIngredients","requiredIngredients","excludedIngredients","limit"));
        String action=CookingArguments.text(args,"action",true);
        if(!Set.of("search","detail","substitute").contains(action)) throw new IllegalArgumentException("action 必须是 search/detail/substitute");
        String id=CookingArguments.text(args,"recipeId",false), query=CookingArguments.text(args,"query",false);
        String ingredient=CookingArguments.text(args,"ingredient",action.equals("substitute"));
        if(!id.isEmpty() && !id.matches("recipe_[0-9]{2}")) throw new IllegalArgumentException("食谱 ID 格式无效");
        if(action.equals("detail") && id.isEmpty() && query.isEmpty() || action.equals("substitute") && id.isEmpty())
            throw new IllegalArgumentException("请提供食谱 ID，查看做法也可提供准确菜名");
        if(!ingredient.isEmpty()) ingredient=new com.diet.service.meal.constraint.IngredientCatalog().normalize(ingredient);
        return new CookingRequest(action,id,query,ingredient,CookingArguments.ingredients(args,"availableIngredients"),
                CookingArguments.ingredients(args,"requiredIngredients"),CookingArguments.ingredients(args,"excludedIngredients"),CookingArguments.limit(args));
    }
}
