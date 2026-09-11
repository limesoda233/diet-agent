package com.diet.service.retrieval;

import com.diet.model.SlotBundle;
import java.util.*;

public final class RetrievalSlots {
    public static final List<String> NAMES = List.of("mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience");
    private RetrievalSlots() {}
    public static Map<String, List<String>> from(SlotBundle slots) {
        SlotBundle s = slots == null ? SlotBundle.empty() : slots;
        var result = new LinkedHashMap<String, List<String>>();
        result.put("mealTime", s.mealTime()); result.put("mood", s.mood()); result.put("scene", s.scene());
        result.put("healthGoal", s.healthGoal()); result.put("cuisine", s.cuisine()); result.put("taste", s.taste()); result.put("convenience", s.convenience());
        return result;
    }
    public static double softScore(SlotBundle preferences, SlotBundle meal) {
        var wanted = from(preferences); var actual = from(meal);
        return wanted.entrySet().stream().filter(e -> !e.getKey().equals("mealTime") && !e.getValue().isEmpty())
                .mapToDouble(e -> e.getValue().stream().filter(actual.get(e.getKey())::contains).count() / (double)e.getValue().size())
                .average().orElse(0);
    }
}
