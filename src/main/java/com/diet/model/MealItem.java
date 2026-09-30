package com.diet.model;

import com.diet.enums.SourceMode;
import com.diet.service.meal.knowledge.MealKnowledge;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
@AllArgsConstructor
public class MealItem {
    private Long id;
    private SourceMode sourceType;
    private Long ownerUserId;
    private String name;
    private SlotBundle slots;
    private double matchScore;
    private MealKnowledge knowledge;

    public MealItem(Long id, SourceMode sourceType, Long ownerUserId, String name, SlotBundle slots, double matchScore) {
        this(id, sourceType, ownerUserId, name, slots, matchScore, null);
    }

    public double matchScore() {
        return matchScore;
    }
}
