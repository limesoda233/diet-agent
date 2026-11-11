package com.diet.service.meal.planning;

import java.util.*;
import static com.diet.service.meal.planning.PlanningTypes.*;

/** Final authority for task scope, candidate membership and verifiable facts. */
public final class PlanValidator {
    public List<String> validateSpec(Range range,FrozenContext context,Spec spec) {
        List<String> errors=new ArrayList<>();
        if(!spec.clarifications().isEmpty())return List.of("PLANNER_CLARIFICATION");
        Map<String,Task> expected=new LinkedHashMap<>();
        for(int d=0;d<range.days();d++)for(String meal:range.mealTimes()) {
            String id="d"+d+"-"+switch(meal){case "早餐"->"breakfast";case "午餐"->"lunch";default->"dinner";};
            expected.put(id,new Task(id,d,meal,List.of()));
        }
        Set<String> seen=new HashSet<>();
        for(Task task:spec.tasks()) {
            Task target=expected.get(task.taskId());
            if(target==null||target.dayOffset()!=task.dayOffset()||!target.mealTime().equals(task.mealTime())||!seen.add(task.taskId()))errors.add("TASK_SCOPE");
            for(String ref:task.preferenceRefs())if(context.preferences().stream().noneMatch(p->p.preferenceId().equals(ref)&&(p.taskIds().isEmpty()||p.taskIds().contains(task.taskId()))))errors.add("PREFERENCE_SCOPE");
            for(Preference p:context.preferences())if((p.taskIds().isEmpty()||p.taskIds().contains(task.taskId()))&&!task.preferenceRefs().contains(p.preferenceId()))errors.add("PREFERENCE_OMITTED");
        }
        if(!seen.equals(expected.keySet())||spec.tasks().size()!=expected.size())errors.add("TASK_COVERAGE");
        return List.copyOf(errors);
    }
    public boolean eligible(Input input,FrozenContext context,Task task,Candidate c) {
        if(c.mealId()<=0||c.sourceMode()!=input.sourceMode()||c.name()==null||c.name().isBlank())return false;
        if(input.sourceMode()==com.diet.enums.SourceMode.PERSONAL&&!Objects.equals(c.ownerUserId(),input.userId()))return false;
        if(input.sourceMode()==com.diet.enums.SourceMode.PUBLIC&&c.ownerUserId()!=null)return false;
        if(!c.mealTimes().contains(task.mealTime()))return false;
        var hard=context.hardConstraints();
        if((!hard.requiredIngredients().isEmpty()||!hard.excludedIngredients().isEmpty())&&!c.ingredientsKnown())return false;
        if(!c.ingredients().containsAll(hard.requiredIngredients())||!Collections.disjoint(c.ingredients(),hard.excludedIngredients()))return false;
        for(var required:hard.requiredSlots().entrySet())if(Collections.disjoint(c.tags().getOrDefault(required.getKey(),List.of()),required.getValue()))return false;
        for(var excluded:hard.excludedSlots().entrySet())if(!Collections.disjoint(c.tags().getOrDefault(excluded.getKey(),List.of()),excluded.getValue()))return false;
        return c.fingerprint()!=null&&!c.fingerprint().isBlank();
    }
    public Validation validate(Input input,FrozenContext context,Range range,Spec spec,Snapshot snapshot,Draft draft) {
        List<String> errors=new ArrayList<>();List<Gap> gaps=new ArrayList<>();List<Assignment> choices=new ArrayList<>();
        Set<String> ids=new HashSet<>();spec.tasks().forEach(t->ids.add(t.taskId()));
        if(draft.assignments().stream().anyMatch(a->!ids.contains(a.taskId()))||draft.gaps().stream().anyMatch(g->!ids.contains(g.taskId())))errors.add("UNKNOWN_TASK");
        Set<Long> used=new HashSet<>();
        for(Task task:spec.tasks()) {
            List<Assignment> matches=draft.assignments().stream().filter(a->a.taskId().equals(task.taskId())).toList();
            List<Gap> taskGaps=draft.gaps().stream().filter(g->g.taskId().equals(task.taskId())).toList();
            String error=null;Candidate selected=null;
            Assignment a=matches.size()==1?matches.get(0):null;
            if(a==null)error="ASSIGNMENT_CARDINALITY";
            else if(a.mealId()!=null) {
                Pool pool=snapshot.pools().get(task.taskId());
                if(pool!=null&&!pool.failed())selected=pool.candidates().stream().filter(c->c.mealId()==a.mealId()).findFirst().orElse(null);
                if(selected==null)error="CANDIDATE_MEMBERSHIP";
                else if(!eligible(input,context,task,selected))error="HARD_CONSTRAINT";
                else if(!taskGaps.isEmpty())error="GAP_WITH_SELECTION";
                else if(range.repeatPolicy()==RepeatPolicy.HARD&&used.contains(a.mealId()))error="HARD_REPEAT";
                else {Candidate meal=selected;if(a.reasonFactRefs().stream().anyMatch(r->!r.startsWith("meal:"+meal.mealId()+":")||!meal.facts().containsKey(r)))error="FACT_REFERENCE";}
            } else if(!a.reasonFactRefs().isEmpty())error="FACT_WITHOUT_SELECTION";
            else if(taskGaps.size()!=1)error="MISSING_GAP";
            if(error!=null) {
                errors.add(task.taskId()+":"+error);choices.add(new Assignment(task.taskId(),null,List.of()));gaps.add(new Gap(task.taskId(),"INVALID_ASSIGNMENT"));
            } else if(a.mealId()==null) {
                choices.add(a);Pool pool=snapshot.pools().get(task.taskId());String code=taskGaps.get(0).code();
                if(pool==null||pool.failed())code="RETRIEVAL_FAILED";
                else if(pool.candidates().isEmpty())code="NO_MATCH";
                else if(code.equals("RETRIEVAL_FAILED")||code.equals("NO_MATCH"))code="UNSATISFIED_PREFERENCE";
                gaps.add(new Gap(task.taskId(),code));
            }
            else {choices.add(a);used.add(a.mealId());}
        }
        return new Validation(choices,gaps,errors);
    }
    /** Augmenting paths preserve scarce choices and maximize cardinality. */
    public Map<String,Long> maximumMatching(Spec spec,Snapshot snapshot) {
        Map<Long,String> owners=new LinkedHashMap<>();Map<String,Long> selected=new LinkedHashMap<>();
        for(Task task:spec.tasks())augment(task.taskId(),snapshot,owners,selected,new HashSet<>());
        return Collections.unmodifiableMap(selected);
    }
    private boolean augment(String taskId,Snapshot snapshot,Map<Long,String> owners,Map<String,Long> selected,Set<Long> visited) {
        Pool pool=snapshot.pools().get(taskId);if(pool==null||pool.failed())return false;
        for(Candidate c:pool.candidates())if(visited.add(c.mealId())) {
            String previous=owners.get(c.mealId());
            if(previous==null||augment(previous,snapshot,owners,selected,visited)) {owners.put(c.mealId(),taskId);selected.put(taskId,c.mealId());return true;}
        }
        return false;
    }
    public Draft deterministic(Spec spec,Snapshot snapshot,RepeatPolicy policy) {
        Map<String,Long> distinct=maximumMatching(spec,snapshot);List<Assignment> choices=new ArrayList<>();List<Gap> gaps=new ArrayList<>();
        for(Task task:spec.tasks()) {
            Pool pool=snapshot.pools().get(task.taskId());Long id=distinct.get(task.taskId());
            if(id==null&&policy!=RepeatPolicy.HARD&&pool!=null&&!pool.failed()&&!pool.candidates().isEmpty())id=pool.candidates().get(0).mealId();
            choices.add(new Assignment(task.taskId(),id,List.of()));
            if(id==null)gaps.add(new Gap(task.taskId(),pool==null||pool.failed()?"RETRIEVAL_FAILED":pool.candidates().isEmpty()?"NO_MATCH":"NO_DISTINCT_CANDIDATE"));
        }
        return new Draft(choices,gaps);
    }
}
