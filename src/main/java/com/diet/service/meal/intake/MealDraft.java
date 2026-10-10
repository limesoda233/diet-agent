package com.diet.service.meal.intake;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MealDraft(String draftId, String originalText, MealDraftData data,
        List<String> missingFields, List<String> warnings, Map<String,List<String>> evidence,
        MealDraftStatus status, Long savedMealId) {
    public MealDraft {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        var copied = new LinkedHashMap<String,List<String>>();
        if (evidence != null) evidence.forEach((key,values) -> copied.put(key,List.copyOf(values)));
        evidence = Collections.unmodifiableMap(copied);
    }
    public MealDraft saved(Long mealId) {
        return new MealDraft(draftId,originalText,data,missingFields,warnings,evidence,MealDraftStatus.SAVED,mealId);
    }
    public MealDraft cancelled() {
        return new MealDraft(draftId,originalText,data,missingFields,warnings,evidence,MealDraftStatus.CANCELLED,savedMealId);
    }
}
