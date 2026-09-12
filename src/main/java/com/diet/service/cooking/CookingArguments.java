package com.diet.service.cooking;

import com.diet.service.retrieval.IngredientCatalog;
import java.util.*;

public final class CookingArguments {
    private CookingArguments() {}
    public static void fields(Map<String,Object> args, Set<String> allowed) {
        if(args==null || !allowed.containsAll(args.keySet()) || args.values().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("工具参数包含未知字段或 null");
    }
    public static String text(Map<String,Object> args,String key,boolean required) {
        Object value=args.get(key);
        if(value==null && !required) return "";
        if(!(value instanceof String s) || s.length()>500 || required && s.isBlank())
            throw new IllegalArgumentException(key+" 必须是有效文本");
        return ((String)value).trim();
    }
    public static int limit(Map<String,Object> args) {
        Object value=args.getOrDefault("limit",3);
        if(!(value instanceof Integer || value instanceof Long) || ((Number)value).longValue()<1 || ((Number)value).longValue()>5)
            throw new IllegalArgumentException("limit 必须是 1 到 5 的整数");
        return ((Number)value).intValue();
    }
    public static List<String> ingredients(Map<String,Object> args,String key) {
        if(!args.containsKey(key)) return List.of();
        if(!(args.get(key) instanceof List<?> values) || values.size()>64)
            throw new IllegalArgumentException(key+" 必须是至多 64 项的食材数组");
        var catalog=new IngredientCatalog();
        var result=new LinkedHashSet<String>();
        for(Object value:values) {
            if(!(value instanceof String text) || text.isBlank() || text.length()>64) throw new IllegalArgumentException("食材名称无效");
            result.add(catalog.normalize(text.trim()));
        }
        return List.copyOf(result);
    }
}
