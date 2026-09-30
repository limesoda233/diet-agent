package com.diet.service.retrieval;

import com.diet.service.meal.knowledge.MealKnowledge;

import com.diet.enums.SourceMode;
import com.diet.model.MealItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public record MealDocument(MealItem meal, MealKnowledge knowledge) {
    public static final String TEMPLATE_VERSION = "meal-v1";
    public String text() {
        return "名称：" + meal.name() + "\n标签：" + RetrievalSlots.from(meal.slots()) +
                (knowledge == null ? "\n食材与做法：未记录" : "\n主要食材：" + knowledge.ingredients() + "\n描述：" + knowledge.description()
                        + "\n做法：" + knowledge.preparation() + "\n来源类型：" + knowledge.sourceType());
    }
    public String contentHash() {
        try {
            String canonical = new ObjectMapper().writeValueAsString(Arrays.asList(TEMPLATE_VERSION, meal.id(), meal.sourceType(), meal.ownerUserId(), text(), knowledge));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法计算餐食证据版本", e); }
    }
    public Map<String,Object> payload() {
        var data = new LinkedHashMap<String,Object>();
        data.put("mealId", meal.id()); data.put("sourceType", meal.sourceType().name());
        data.put("ownerUserId", meal.ownerUserId() == null ? 0 : meal.ownerUserId());
        data.putAll(RetrievalSlots.from(meal.slots())); data.put("contentHash", contentHash());
        data.put("ingredientDataPresent", knowledge != null && knowledge.ingredientDataPresent());
        data.put("ingredientIds", knowledge == null ? List.of() : knowledge.ingredients());
        return data;
    }
    public List<String> violations(MealRetrievalRequest request) {
        var reasons = new ArrayList<String>();
        boolean scope = meal.sourceType() == request.sourceMode() && (request.sourceMode() == SourceMode.PUBLIC
                ? meal.ownerUserId() == null : Objects.equals(meal.ownerUserId(), request.userId()));
        if (!scope) reasons.add("sourceScope");
        if (request.excludeMealIds().contains(meal.id())) reasons.add("excludedMealId");
        if (!request.slots().mealTime().isEmpty() && Collections.disjoint(request.slots().mealTime(), meal.slots().mealTime())) reasons.add("mealTime");
        var constraints = request.constraints();
        if (!constraints.requiredIngredients().isEmpty() || !constraints.excludedIngredients().isEmpty()) {
            if (knowledge == null || !knowledge.ingredientDataPresent()) reasons.add("ingredientDataMissing");
            else {
                if (!knowledge.ingredients().containsAll(constraints.requiredIngredients())) reasons.add("missingRequiredIngredient");
                if (!Collections.disjoint(knowledge.ingredients(), constraints.excludedIngredients())) reasons.add("excludedIngredient");
            }
        }
        var slots = RetrievalSlots.from(meal.slots());
        constraints.requiredSlots().forEach((key, values) -> { if (Collections.disjoint(slots.get(key), values)) reasons.add("requiredSlot:" + key); });
        constraints.excludedSlots().forEach((key, values) -> { if (!Collections.disjoint(slots.get(key), values)) reasons.add("excludedSlot:" + key); });
        return List.copyOf(reasons);
    }
}
