package com.diet.service.cooking;

import com.diet.service.retrieval.MealConstraints;
import com.diet.service.trace.AgentTraceService;
import java.util.*;

public final class CookingService {
    private final RecipeProvider client;
    private final CookingProperties properties;
    private final AgentTraceService trace;
    private final CookingRouter router=new CookingRouter();
    public CookingService(RecipeProvider client,CookingProperties properties,AgentTraceService trace) {
        this.client=client; this.properties=properties; this.trace=trace;
    }
    public boolean enabled() { return properties.isEnabled(); }
    public boolean matches(String message,Map<String,Object> context) {
        return context!=null && context.containsKey("cookingRequest") || enabled() && router.matches(message,context);
    }
    public CookingRequest route(String message,Map<String,Object> context) {
        return router.route(message,context).orElseThrow(()->new IllegalArgumentException("请明确查询食谱、查看做法或替换食材"));
    }
    public String respond(CookingRequest request,MealConstraints session) {
        if(!enabled()) return "食谱工具尚未启用，请启用 diet.cooking.enabled 后再使用。";
        if(!session.requiredSlots().isEmpty() || !session.excludedSlots().isEmpty())
            throw new IllegalArgumentException("当前示例食谱没有足够的餐次或口味标签，无法核对已有标签限制；请先明确调整限制");
        var required=union(session.requiredIngredients(),request.requiredIngredients());
        var excluded=union(session.excludedIngredients(),request.excludedIngredients());
        var calls=new Calls();
        try {
            if(request.action().equals("substitute")) return substitutions(request,required,excluded,calls);
            List<Map<String,Object>> recipes;
            if(request.action().equals("search") || request.recipeId().isEmpty()) {
                var result=calls.call("search_recipes",Map.of("availableIngredients",request.availableIngredients(),"requiredIngredients",required,
                        "excludedIngredients",excluded,"query",request.query(),"limit",request.action().equals("detail")?5:request.limit()));
                recipes=records(result,"recipes",5);
                for(var recipe:recipes) validateRecipe(recipe,required,excluded);
                if(recipes.isEmpty()) return "没有找到满足这些条件的已记录食谱。";
                if(request.action().equals("search")) return searchText(recipes,request.availableIngredients());
                if(recipes.size()!=1) return "有多个匹配，请指定食谱 ID：\n"+searchText(recipes,request.availableIngredients());
            } else recipes=List.of(Map.of("recipeId",request.recipeId()));
            String id=(String)recipes.get(0).get("recipeId");
            var recipe=object(calls.call("get_recipe",Map.of("recipeId",id,"excludedIngredients",excluded)).get("recipe"));
            validateRecipe(recipe,required,excluded);
            if(!id.equals(recipe.get("recipeId"))) throw new IllegalStateException("recipe_id_mismatch");
            var steps=strings(recipe,"steps");
            var output=new StringBuilder(header(recipe)).append("\n食材：").append(String.join("、",strings(recipe,"ingredients")));
            for(int i=0;i<steps.size();i++) output.append("\n").append(i+1).append(". ").append(steps.get(i));
            return output.toString();
        } catch(IllegalArgumentException invalid) { throw invalid; }
        catch(RuntimeException failure) { return "食谱工具暂时不可用，无法提供已核对的做法或替换建议，请稍后重试。"; }
    }
    private String substitutions(CookingRequest request,List<String> required,List<String> excluded,Calls calls) {
        var result=calls.call("suggest_substitutions",Map.of("recipeId",request.recipeId(),"ingredient",request.ingredient(),
                "availableIngredients",request.availableIngredients(),"excludedIngredients",excluded));
        var rules=records(result,"substitutions",8);
        if(rules.isEmpty()) return "没有适用于该食谱且满足当前限制的已记录替换规则。";
        // The original is evidence for replacement validation, never a recommended dish.
        // It can contain the ingredient being excluded and replaced.
        var original=object(calls.call("get_recipe",Map.of("recipeId",request.recipeId(),"excludedIngredients",List.of())).get("recipe"));
        validateRecipe(original,List.of(),List.of());
        var originalIngredients=CookingArguments.ingredients(original,"ingredients");
        if(!request.recipeId().equals(original.get("recipeId")) || !originalIngredients.contains(request.ingredient()))
            throw new IllegalStateException("substitution_original_invalid");
        var output=new ArrayList<String>();
        try {
        for(var rule:rules) {
            if(!request.recipeId().equals(rule.get("recipeId")) || !request.ingredient().equals(rule.get("ingredient")))
                throw new IllegalStateException("substitution_scope_invalid");
            var ingredients=CookingArguments.ingredients(rule,"resultingIngredients");
            String replacement=CookingArguments.text(rule,"replacement",true);
            var expected=new HashSet<>(originalIngredients);
            expected.remove(request.ingredient()); expected.add(replacement);
            if(!new HashSet<>(ingredients).equals(expected) || ingredients.contains(request.ingredient())) throw new IllegalStateException("substitution_result_invalid");
            if(!ingredients.containsAll(required) || !Collections.disjoint(ingredients,excluded)) continue;
            if(!request.availableIngredients().isEmpty() && !request.availableIngredients().contains(replacement)) throw new IllegalStateException("substitution_unavailable_ingredient");
            output.add(request.recipeId()+"："+request.ingredient()+" → "+replacement+"\n适用步骤："+CookingArguments.text(rule,"applicableStep",true)
                    +"\n影响："+CookingArguments.text(rule,"effect",true)+"\n替换比例未记录。\n"+source(rule));
        }
        } catch(IllegalArgumentException invalid) { throw new IllegalStateException("substitution_response_invalid",invalid); }
        return output.isEmpty()?"没有适用于该食谱且满足当前限制的已记录替换规则。":String.join("\n\n",output);
    }
    private String searchText(List<Map<String,Object>> recipes,List<String> available) {
        var lines=new ArrayList<String>();
        for(var recipe:recipes) {
            var missing=strings(recipe,"ingredients").stream().filter(x->!available.contains(x)).toList();
            lines.add(header(recipe)+"\n还缺少："+(missing.isEmpty()?"无（按记录食材核对）":String.join("、",missing)));
        }
        return String.join("\n\n",lines);
    }
    private static String header(Map<String,Object> recipe) { return CookingArguments.text(recipe,"name",true)+" ["+recipe.get("recipeId")+"]\n"+source(recipe); }
    private static String source(Map<String,Object> record) {
        String type=CookingArguments.text(record,"sourceType",true);
        if(!Set.of("LOCAL_EXAMPLE","IMPORTED").contains(type)) throw new IllegalStateException("recipe_source_invalid");
        return "来源："+type+" · "+CookingArguments.text(record,"sourceReference",true)+" · v"+CookingArguments.text(record,"version",true)
                +(type.equals("LOCAL_EXAMPLE")?"（本地示例，未经独立核实）":"");
    }
    private static void validateRecipe(Map<String,Object> recipe,List<String> required,List<String> excluded) {
        try {
            if(!CookingArguments.text(recipe,"recipeId",true).matches("recipe_[0-9]{2}")) throw new IllegalStateException("recipe_id_invalid");
            CookingArguments.text(recipe,"name",true); source(recipe);
            var ingredients=CookingArguments.ingredients(recipe,"ingredients");
            if(ingredients.isEmpty() || !ingredients.containsAll(required) || !Collections.disjoint(ingredients,excluded) || strings(recipe,"steps").isEmpty())
                throw new IllegalStateException("recipe_constraints_invalid");
        } catch(IllegalArgumentException invalid) { throw new IllegalStateException("recipe_response_invalid",invalid); }
    }
    private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> map) || map.keySet().stream().anyMatch(k->!(k instanceof String))) throw new IllegalStateException("recipe_object_invalid");
        @SuppressWarnings("unchecked") var result=(Map<String,Object>)map; return result;
    }
    private static List<Map<String,Object>> records(Map<String,Object> result,String key,int max) {
        if(!(result.get(key) instanceof List<?> rows) || rows.size()>max) throw new IllegalStateException("recipe_list_invalid");
        return rows.stream().map(CookingService::object).toList();
    }
    private static List<String> strings(Map<String,Object> value,String key) {
        if(!(value.get(key) instanceof List<?> rows) || rows.size()>64 || rows.stream().anyMatch(x->!(x instanceof String s) || s.isBlank() || s.length()>1000))
            throw new IllegalStateException("recipe_strings_invalid");
        return rows.stream().map(String.class::cast).toList();
    }
    private static List<String> union(List<String> first,List<String> second) { var result=new LinkedHashSet<>(first); result.addAll(second); return List.copyOf(result); }
    private final class Calls {
        private int count;
        Map<String,Object> call(String name,Map<String,Object> args) {
            if(++count>3) throw new IllegalStateException("cooking_call_limit");
            long started=System.nanoTime();
            try {
                var result=client.call(name,args);
                trace.recordEvent("COOKING_TOOL_CALLED","COOKING",Map.of("tool",name,"arguments",args),Map.of("status","success","result",result), (System.nanoTime()-started)/1_000_000);
                return result;
            } catch(RuntimeException failure) {
                trace.recordEvent("COOKING_TOOL_CALLED","COOKING",Map.of("tool",name,"arguments",args),Map.of("status","error","category",failure instanceof IllegalArgumentException?"invalid_request":"dependency_failure"),(System.nanoTime()-started)/1_000_000);
                throw failure;
            }
        }
    }
}
