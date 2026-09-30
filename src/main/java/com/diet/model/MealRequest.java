package com.diet.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.diet.service.meal.knowledge.MealKnowledge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class MealRequest {
    private String name;
    private List<String> mealTime;
    private List<String> mood;
    private List<String> scene;
    private List<String> healthGoal;
    private List<String> cuisine;
    private List<String> taste;
    private List<String> convenience;
    private MealKnowledge knowledge;
    @JsonIgnore
    private boolean knowledgeProvided;

    @JsonSetter("knowledge")
    public MealRequest knowledge(MealKnowledge value) {
        knowledge = value; knowledgeProvided = true; return this;
    }

    public MealRequest(String name, List<String> mealTime, List<String> mood, List<String> scene,
                       List<String> healthGoal, List<String> cuisine, List<String> taste, List<String> convenience) {
        this(name, mealTime, mood, scene, healthGoal, cuisine, taste, convenience, null, false);
    }

    public SlotBundle toSlots() {
        return new SlotBundle(mealTime, mood, scene, healthGoal, cuisine, taste, convenience);
    }
}
