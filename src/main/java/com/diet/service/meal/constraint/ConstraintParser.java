package com.diet.service.meal.constraint;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Bounded grammar: unrecognized explicit restrictions require clarification. */
@Component
public class ConstraintParser {
    private static final Pattern NEGATIVE = Pattern.compile("(?:不吃|不要|排除|别放|不放)([^，。；！？]+)");
    private static final Pattern REQUIRED = Pattern.compile("(?:同时(?:用|使用|有)|必须(?:用|含|包含)|一定要(?:有|用)|用上)([^，。；！？]+)");
    private static final Pattern SUFFIX_REQUIRED = Pattern.compile("([\\p{IsHan}]+)必须用上");
    private static final Pattern CANCEL = Pattern.compile("取消([^，。；！？]+?)限制");
    private static final Pattern CLEAR_ALL = Pattern.compile("(?:^|[，。；！？])\\s*取消所有限制\\s*(?=$|[，。；！？])");
    private static final Pattern UNRESOLVED = Pattern.compile("必须|一定要|只要|只能|不能吃|忌口|禁用|不可以吃|不要|不吃|别放|不放|排除|用上|取消.*限制");
    private final IngredientCatalog catalog;
    public ConstraintParser(IngredientCatalog catalog) { this.catalog = catalog; }

    public MealConstraintContext resolveContext(String text, MealConstraintContext previous, Map<String,Object> context, boolean carryNeed) {
        boolean structured=context!=null && context.containsKey("retrievalConstraints");
        var base=previous.constraints();
        String constraintText=text;
        if(!previous.pendingConstraintText().isBlank() && !structured) {
            if(text.startsWith("确认全部限制：")) {
                constraintText=text.substring("确认全部限制：".length());
                if(!UNRESOLVED.matcher(constraintText).find()) throw new IllegalArgumentException("请在确认全部限制后明确说明包含或排除条件");
                base=MealConstraints.empty();
            } else if(!text.trim().equals("取消所有限制")) {
                throw new IllegalArgumentException("上一条限制尚未确认。请用“确认全部限制：必须含番茄，不要牛肉”重述全部限制，或回复“取消所有限制”");
            }
        }
        var constraints=resolve(constraintText,base,context);
        String query=text;
        if(carryNeed && !previous.queryText().isBlank()) {
            boolean anotherBatch=text.trim().matches("(?:请)?(?:再)?(?:换一批|换几个|来一批|换一组)[。！!]?$");
            query=anotherBatch?previous.queryText():previous.queryText()+"；本轮调整："+text;
        }
        return new MealConstraintContext(query,constraints,structured?"structured":"rule_and_session");
    }

    public MealConstraints resolve(String text, MealConstraints previous, Map<String,Object> context) {
        if (context != null && context.containsKey("retrievalConstraints")) return structured(context.get("retrievalConstraints"));
        if (text == null || text.length() > 8000) throw new IllegalArgumentException("检索需求过长或为空");
        var base = previous == null ? MealConstraints.empty() : previous;
        if (text.contains("取消所有限制")) {
            if (!CLEAR_ALL.matcher(text).find()) throw new IllegalArgumentException("取消限制的表达有歧义，请明确确认");
            base = MealConstraints.empty();
            text = CLEAR_ALL.matcher(text).replaceAll("，");
        }
        if (Pattern.compile("(?:不要|不吃|别放)(?:不|没有)|不是不要|并非不吃").matcher(text).find())
            throw new IllegalArgumentException("这句限制有歧义，请明确要包含还是排除哪些食材");
        var required = new LinkedHashSet<>(base.requiredIngredients());
        var excluded = new LinkedHashSet<>(base.excludedIngredients());
        var requiredSlots = new TreeMap<>(base.requiredSlots());
        var excludedSlots = new TreeMap<>(base.excludedSlots());
        var cancel = CANCEL.matcher(text);
        while (cancel.find()) {
            String ingredient = catalog.normalize(cancel.group(1)); required.remove(ingredient); excluded.remove(ingredient);
        }
        String remaining = CANCEL.matcher(text).replaceAll("").replaceAll("但是|但", "，");
        var negative = NEGATIVE.matcher(remaining);
        while (negative.find()) {
            String clause = negative.group(1);
            if (clause.equals("辣") || clause.equals("辛辣")) excludedSlots.put("taste", List.of("辣", "微辣", "中辣", "麻辣"));
            else excluded.addAll(ingredients(clause));
        }
        remaining = NEGATIVE.matcher(remaining).replaceAll("");
        var requiredSuffix = SUFFIX_REQUIRED.matcher(remaining);
        while (requiredSuffix.find()) required.addAll(requiredIngredients(requiredSuffix.group(1)));
        remaining = SUFFIX_REQUIRED.matcher(remaining).replaceAll("");
        var positive = REQUIRED.matcher(remaining);
        while (positive.find()) required.addAll(requiredIngredients(positive.group(1)));
        remaining = REQUIRED.matcher(remaining).replaceAll("");
        if (UNRESOLVED.matcher(remaining).find()) throw new IllegalArgumentException("检测到明确限制但暂不能准确转换，请明确要包含或排除的食材/标签");
        var result = new MealConstraints(List.copyOf(required), List.copyOf(excluded), requiredSlots, excludedSlots);
        catalog.validate(result);
        return result;
    }
    private List<String> ingredients(String clause) {
        String value = clause.replaceFirst("(?:的(?:菜|面食|主食)|做(?:菜|面|饭)).*$", "");
        return Arrays.stream(value.split("和|及|与|、|加|或|还有", -1)).map(String::trim).map(catalog::normalize).distinct().toList();
    }
    private List<String> requiredIngredients(String clause) {
        if (clause.contains("或")) throw new IllegalArgumentException("请明确选择要使用的食材，当前不能把二选一解释为同时必需");
        return ingredients(clause);
    }
    private MealConstraints structured(Object raw) {
        if (!(raw instanceof Map<?,?> map)) throw new IllegalArgumentException("retrievalConstraints 必须是对象");
        var fields = Set.of("requiredIngredients", "excludedIngredients", "requiredSlots", "excludedSlots");
        if (!fields.containsAll(map.keySet())) throw new IllegalArgumentException("retrievalConstraints 包含未知字段");
        if (map.values().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("限制字段不能为 null；清除全部限制请使用空对象");
        var result = new MealConstraints(strings(map.get("requiredIngredients")).stream().map(catalog::normalize).toList(),
                strings(map.get("excludedIngredients")).stream().map(catalog::normalize).toList(), slotMap(map.get("requiredSlots")), slotMap(map.get("excludedSlots")));
        catalog.validate(result); return result;
    }
    private static List<String> strings(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String))) throw new IllegalArgumentException("限制值必须是字符串数组");
        return list.stream().map(String.class::cast).toList();
    }
    private static Map<String,List<String>> slotMap(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?,?> map)) throw new IllegalArgumentException("标签限制必须是对象");
        var result = new TreeMap<String,List<String>>();
        map.forEach((key, value) -> {
            if (!(key instanceof String)) throw new IllegalArgumentException("标签限制键必须为字符串");
            if (value == null) throw new IllegalArgumentException("标签数组不能为 null");
            result.put((String)key, strings(value));
        });
        return result;
    }
}
