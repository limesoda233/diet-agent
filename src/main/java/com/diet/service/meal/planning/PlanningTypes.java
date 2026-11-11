package com.diet.service.meal.planning;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import com.diet.service.meal.constraint.MealConstraints;
import java.time.*;
import java.util.*;

/** Immutable server-owned data crossing the bounded planning workflow. */
public final class PlanningTypes {
    private PlanningTypes() {}
    public enum Status { COMPLETE, PARTIAL, DEGRADED, NEEDS_INPUT, UNAVAILABLE }
    public enum Role { PLANNER, MENU, REVIEW }
    public enum RepeatPolicy { ALLOW, SOFT, HARD }
    public enum ReviewDecision { PASS, REVISE, NEEDS_INPUT }
    public enum Severity { HARD, SOFT }
    public record Input(Long userId, String sessionId, String traceId, SourceMode sourceMode,
                        String message, SlotBundle slots, MealConstraints constraints, Map<String,Object> structured) {
        public Input {
            Objects.requireNonNull(userId); Objects.requireNonNull(sourceMode);
            message = Objects.requireNonNullElse(message, "");
            slots = copySlots(slots == null ? SlotBundle.empty() : slots);
            constraints = constraints == null ? MealConstraints.empty() : constraints;
            structured = immutableData(structured == null ? Map.of() : structured);
        }
        @Override public SlotBundle slots() { return copySlots(slots); }
    }
    public record FrozenContext(String memoryEpoch, Map<String,Long> memoryItemVersions,
                                MealConstraints hardConstraints, List<Preference> preferences,
                                List<String> unsupportedConstraints, List<String> blockers) {
        public FrozenContext {
            memoryItemVersions = Map.copyOf(memoryItemVersions); Objects.requireNonNull(hardConstraints);
            preferences=List.copyOf(preferences); unsupportedConstraints=List.copyOf(unsupportedConstraints); blockers=List.copyOf(blockers);
        }
    }
    public record Preference(String preferenceId,String dimension,String value,List<String> taskIds,String sourceRef) {
        public Preference { taskIds=List.copyOf(taskIds); }
    }
    public record Range(LocalDate startDate,int days,List<String> mealTimes,RepeatPolicy repeatPolicy,List<String> clarifications) {
        public Range {mealTimes=List.copyOf(mealTimes);clarifications=List.copyOf(clarifications);}
    }
    public record Task(String taskId,int dayOffset,String mealTime,List<String> preferenceRefs) {
        public Task { preferenceRefs=List.copyOf(preferenceRefs); }
    }
    public record Spec(List<Task> tasks,List<String> clarifications) {
        public Spec { tasks=List.copyOf(tasks); clarifications=List.copyOf(clarifications); }
    }
    public record Candidate(long mealId,SourceMode sourceMode,Long ownerUserId,String name,
                            List<String> mealTimes,List<String> ingredients,boolean ingredientsKnown,
                            Map<String,List<String>> tags,Map<String,String> facts,String fingerprint) {
        public Candidate {
            mealTimes=List.copyOf(mealTimes); ingredients=List.copyOf(ingredients); tags=copyMap(tags);facts=Map.copyOf(facts);
        }
    }
    public record Pool(List<Candidate> candidates,boolean failed,boolean truncated) {
        public Pool { candidates=List.copyOf(candidates); }
    }
    public record Snapshot(Map<String,Pool> pools,boolean expanded,boolean truncated) {
        public Snapshot { pools=Collections.unmodifiableMap(new LinkedHashMap<>(pools)); }
    }
    public record Assignment(String taskId,Long mealId,List<String> reasonFactRefs) {
        public Assignment { reasonFactRefs=List.copyOf(reasonFactRefs); }
    }
    public record Gap(String taskId,String code) {}
    public record Draft(List<Assignment> assignments,List<Gap> gaps) {
        public Draft {assignments=List.copyOf(assignments);gaps=List.copyOf(gaps);}
    }
    public record Issue(String issueId,List<String> taskIds,String constraintId,String code,Severity severity,
                        List<String> evidenceRefs,String suggestion) {
        public Issue {taskIds=List.copyOf(taskIds);evidenceRefs=List.copyOf(evidenceRefs);}
    }
    public record Review(ReviewDecision decision,List<Issue> issues) {
        public Review {issues=List.copyOf(issues);}
    }
    public record Validation(List<Assignment> assignments,List<Gap> gaps,List<String> errors) {
        public Validation {assignments=List.copyOf(assignments);gaps=List.copyOf(gaps);errors=List.copyOf(errors);}
        public boolean valid() { return errors.isEmpty(); }
    }
    public record Day(LocalDate date,List<Block> blocks) { public Day {blocks=List.copyOf(blocks);} }
    public record Block(String taskId,String mealTime,Candidate selectedMeal,String reason,boolean repeated) {}
    public record Result(String planId,int version,Status status,String strategy,boolean reviewed,
                         List<Day> days,List<Gap> gaps,List<String> unsupportedConstraints,List<String> warnings) {
        public Result { days=List.copyOf(days);gaps=List.copyOf(gaps);unsupportedConstraints=List.copyOf(unsupportedConstraints);warnings=List.copyOf(warnings); }
        public Result identified(PlanIdentity id) {return new Result(id.planId(),id.version(),status,strategy,reviewed,days,gaps,unsupportedConstraints,warnings);}
    }
    public record PlanIdentity(String planId,int version) {}
    public record Budget(Duration total,Duration context,Duration retrieval,Duration planner,Duration menu,Duration review,int concurrency) {
        public Budget {
            for(Duration d:List.of(total,context,retrieval,planner,menu,review)) if(d.isNegative()||d.isZero())throw new IllegalArgumentException("positive stage budgets required");
            if(concurrency<1||concurrency>3)throw new IllegalArgumentException("retrieval concurrency must be 1..3");
        }
        public static Budget defaults() {return new Budget(Duration.ofSeconds(40),Duration.ofSeconds(5),Duration.ofSeconds(8),Duration.ofSeconds(5),Duration.ofSeconds(8),Duration.ofSeconds(4),3);}
    }
    private static SlotBundle copySlots(SlotBundle b) {return new SlotBundle(b.mealTime(),b.mood(),b.scene(),b.healthGoal(),b.cuisine(),b.taste(),b.convenience());}
    private static Map<String,List<String>> copyMap(Map<String,List<String>> map) {
        Map<String,List<String>> result=new LinkedHashMap<>();map.forEach((k,v)->result.put(k,List.copyOf(v)));return Collections.unmodifiableMap(result);
    }
    @SuppressWarnings("unchecked") private static <T> T immutableData(T value) {
        if(value instanceof Map<?,?> map) {Map<String,Object> copy=new LinkedHashMap<>();map.forEach((k,v)->copy.put(String.valueOf(k),immutableData(v)));return (T)Collections.unmodifiableMap(copy);}
        if(value instanceof List<?> list)return (T)list.stream().map(PlanningTypes::immutableData).toList();
        return value;
    }
}
