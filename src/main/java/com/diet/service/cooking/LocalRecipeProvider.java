package com.diet.service.cooking;

import com.diet.service.meal.constraint.IngredientCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class LocalRecipeProvider implements RecipeProvider {
    public record Recipe(String recipeId,String name,List<String> ingredients,List<String> steps,
                         String sourceType,String sourceReference,String version,String status) {}
    public record Substitution(String ruleId,String recipeId,String ingredient,String replacement,
                               String applicableStep,String effect,String sourceType,String sourceReference,String version) {}
    private final Map<String,Recipe> recipes=new LinkedHashMap<>();
    private final List<Substitution> substitutions;
    private final IngredientCatalog catalog=new IngredientCatalog();
    public LocalRecipeProvider() {
        var json=new ObjectMapper();
        try(var recipeStream=getClass().getResourceAsStream("/diet/cooking/recipes.json");
            var ruleStream=getClass().getResourceAsStream("/diet/cooking/substitutions.json")) {
            for(var recipe:json.readValue(recipeStream,Recipe[].class)) {
                if(!recipe.recipeId().matches("recipe_[0-9]{2}") || recipes.putIfAbsent(recipe.recipeId(),recipe)!=null
                        || recipe.name().isBlank() || recipe.ingredients().isEmpty() || recipe.steps().isEmpty()
                        || !recipe.sourceType().equals("LOCAL_EXAMPLE") || recipe.sourceReference().isBlank()
                        || !recipe.status().equals("example_unverified") || recipe.version().isBlank())
                    throw new IllegalArgumentException("食谱记录无效或重复");
                recipe.ingredients().forEach(catalog::normalize);
                if(new HashSet<>(recipe.ingredients()).size()!=recipe.ingredients().size()) throw new IllegalArgumentException("食谱食材重复");
            }
            substitutions=List.of(json.readValue(ruleStream,Substitution[].class));
            var ids=new HashSet<String>();
            for(var rule:substitutions) {
                var recipe=recipes.get(rule.recipeId());
                if(!ids.add(rule.ruleId()) || recipe==null || !recipe.ingredients().contains(rule.ingredient())
                        || rule.ingredient().equals(rule.replacement()) || rule.applicableStep().isBlank() || rule.effect().isBlank()
                        || !rule.sourceType().equals("LOCAL_EXAMPLE") || rule.sourceReference().isBlank() || rule.version().isBlank())
                    throw new IllegalArgumentException("替换规则无效");
                catalog.normalize(rule.replacement());
            }
        } catch(Exception failure) { throw new IllegalStateException("食谱数据校验失败",failure); }
    }
    @Override public Map<String,Object> call(String tool,Map<String,Object> args) {
        return switch(tool) {
            case "search_recipes" -> search(args);
            case "get_recipe" -> detail(args);
            case "suggest_substitutions" -> substitute(args);
            default -> throw new IllegalArgumentException("未知食谱工具");
        };
    }
    private Map<String,Object> search(Map<String,Object> args) {
        CookingArguments.fields(args,Set.of("availableIngredients","requiredIngredients","excludedIngredients","query","limit"));
        var available=CookingArguments.ingredients(args,"availableIngredients");
        var required=CookingArguments.ingredients(args,"requiredIngredients");
        var excluded=CookingArguments.ingredients(args,"excludedIngredients");
        var query=CookingArguments.text(args,"query",false);
        int limit=CookingArguments.limit(args);
        var found=recipes.values().stream()
                .filter(r->r.ingredients().containsAll(required) && Collections.disjoint(r.ingredients(),excluded))
                .filter(r->query.isEmpty() || r.name().contains(query) || r.recipeId().equals(query))
                .sorted(Comparator.<Recipe>comparingDouble(r->coverage(r,available)).reversed().thenComparing(Recipe::recipeId))
                .limit(limit).map(r->{
                    var result=record(r);
                    result.put("matchedIngredients",r.ingredients().stream().filter(available::contains).toList());
                    result.put("missingIngredients",r.ingredients().stream().filter(x->!available.contains(x)).toList());
                    return result;
                }).toList();
        return Map.of("recipes",found);
    }
    private Map<String,Object> detail(Map<String,Object> args) {
        CookingArguments.fields(args,Set.of("recipeId","excludedIngredients"));
        var recipe=recipe(args);
        if(!Collections.disjoint(recipe.ingredients(),CookingArguments.ingredients(args,"excludedIngredients")))
            throw new IllegalArgumentException("该食谱含有明确禁用的食材");
        return Map.of("recipe",record(recipe));
    }
    private Map<String,Object> substitute(Map<String,Object> args) {
        CookingArguments.fields(args,Set.of("recipeId","ingredient","availableIngredients","excludedIngredients"));
        var recipe=recipe(args);
        var ingredient=catalog.normalize(CookingArguments.text(args,"ingredient",true));
        if(!recipe.ingredients().contains(ingredient)) throw new IllegalArgumentException("待替换食材不属于该食谱");
        var available=CookingArguments.ingredients(args,"availableIngredients");
        var excluded=CookingArguments.ingredients(args,"excludedIngredients");
        var found=substitutions.stream().filter(r->r.recipeId().equals(recipe.recipeId()) && r.ingredient().equals(ingredient))
                .filter(r->available.isEmpty() || available.contains(r.replacement()))
                .filter(r->Collections.disjoint(replacedIngredients(recipe,r),excluded))
                .map(r->{
                    var result=new LinkedHashMap<String,Object>();
                    result.put("ruleId",r.ruleId()); result.put("recipeId",r.recipeId());
                    result.put("ingredient",r.ingredient()); result.put("replacement",r.replacement());
                    result.put("resultingIngredients",replacedIngredients(recipe,r));
                    result.put("applicableStep",r.applicableStep()); result.put("effect",r.effect());
                    result.put("sourceType",r.sourceType()); result.put("sourceReference",r.sourceReference()); result.put("version",r.version());
                    return result;
                }).toList();
        return Map.of("substitutions",found);
    }
    private Recipe recipe(Map<String,Object> args) {
        var recipe=recipes.get(CookingArguments.text(args,"recipeId",true));
        if(recipe==null) throw new IllegalArgumentException("找不到该食谱 ID");
        return recipe;
    }
    private static List<String> replacedIngredients(Recipe recipe,Substitution rule) {
        return recipe.ingredients().stream().map(x->x.equals(rule.ingredient())?rule.replacement():x).distinct().toList();
    }
    private static double coverage(Recipe recipe,List<String> available) {
        return recipe.ingredients().stream().filter(available::contains).count()/(double)recipe.ingredients().size();
    }
    private static Map<String,Object> record(Recipe recipe) {
        var result=new LinkedHashMap<String,Object>();
        result.put("recipeId",recipe.recipeId()); result.put("name",recipe.name()); result.put("ingredients",recipe.ingredients());
        result.put("steps",recipe.steps()); result.put("sourceType",recipe.sourceType()); result.put("sourceReference",recipe.sourceReference());
        result.put("version",recipe.version()); result.put("status",recipe.status()); return result;
    }
}
