package com.diet.service.memory;

import com.diet.model.ChatRequest;
import java.util.*;

/** Server-owned request metadata; always overwrite caller supplied reserved values before routing. */
public final class MemoryRequestContext {
    private static final String KEY="_memoryExcluded";
    private MemoryRequestContext() { }
    public static ChatRequest prepare(ChatRequest request,MemoryTurn turn,boolean recipeMatched) {
        var excluded=new LinkedHashSet<String>();
        turn.facts().stream().filter(f->"AVOID_INGREDIENT".equals(f.type()) && "ADD".equals(f.operation())).forEach(f->excluded.add(f.value()));
        turn.items().stream().filter(i->"AVOID_INGREDIENT".equals(i.type()) && "ACTIVE".equals(i.status())).forEach(i->excluded.add(i.value()));
        Map<String,Object> context=new LinkedHashMap<>();if(request.context()!=null)context.putAll(request.context());
        context.put(KEY,List.copyOf(excluded));
        if(recipeMatched && !excluded.isEmpty()) context=mergeRecipe(context,request.message());
        return new ChatRequest(request.sessionId(),request.message(),request.sourceMode(),context);
    }
    public static List<String> exclusions(Map<String,Object> context) {
        if(context==null || !(context.get(KEY) instanceof List<?> values))return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).distinct().toList();
    }
    public static Map<String,Object> mergeRecipe(Map<String,Object> context,String message) {
        var extra=exclusions(context);if(extra.isEmpty())return context;
        Map<String,Object> result=new LinkedHashMap<>();if(context!=null)result.putAll(context);
        Map<String,Object> recipe=new LinkedHashMap<>();
        if(result.get("recipeQuery") instanceof Map<?,?> original)original.forEach((k,v)->recipe.put(String.valueOf(k),v));
        var all=new LinkedHashSet<>(extra);
        if(recipe.containsKey("excludedIngredients")) {
            if(!(recipe.get("excludedIngredients") instanceof List<?> values))throw new IllegalArgumentException("排除食材必须为数组");
            for(Object value:values) {if(!(value instanceof String text))throw new IllegalArgumentException("排除食材无效");all.add(text);}
        }
        recipe.put("excludedIngredients",List.copyOf(all));recipe.putIfAbsent("query",message);result.put("recipeQuery",recipe);
        return result;
    }
}
