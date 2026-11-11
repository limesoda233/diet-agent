package com.diet.service.meal.planning;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import static com.diet.service.meal.planning.PlanningTypes.*;

/** Closed schemas: no prose extraction, extra fields or unbounded arrays. */
public final class PlanningJsonCodec {
    private final ObjectMapper mapper=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12).maxStringLength(12000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public Spec spec(String raw) {
        JsonNode n=read(raw);keys(n,"tasks","clarifications");List<Task> tasks=new ArrayList<>();
        for(JsonNode t:array(n,"tasks",9)) {keys(t,"taskId","dayOffset","mealTime","preferenceRefs");JsonNode offset=t.get("dayOffset");if(!offset.isIntegralNumber()||!offset.canConvertToInt()||offset.intValue()<0||offset.intValue()>2)throw bad();
            String meal=text(t,"mealTime");if(!List.of("早餐","午餐","晚餐").contains(meal))throw bad();tasks.add(new Task(text(t,"taskId"),offset.intValue(),meal,strings(t,"preferenceRefs",32)));}
        return new Spec(tasks,strings(n,"clarifications",9));
    }
    public Draft draft(String raw) {
        JsonNode n=read(raw);keys(n,"assignments","gaps");List<Assignment> assignments=new ArrayList<>();List<Gap> gaps=new ArrayList<>();
        for(JsonNode a:array(n,"assignments",9)) {keys(a,"taskId","mealId","reasonFactRefs");JsonNode id=a.get("mealId");if(!id.isNull()&&(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0))throw bad();assignments.add(new Assignment(text(a,"taskId"),id.isNull()?null:id.longValue(),strings(a,"reasonFactRefs",16)));}
        for(JsonNode g:array(n,"gaps",9)) {keys(g,"taskId","code");String code=text(g,"code");if(!Set.of("NO_MATCH","NO_DISTINCT_CANDIDATE","RETRIEVAL_FAILED","UNSATISFIED_PREFERENCE").contains(code))throw bad();gaps.add(new Gap(text(g,"taskId"),code));}
        return new Draft(assignments,gaps);
    }
    public Review review(String raw) {
        JsonNode n=read(raw);keys(n,"decision","issues");ReviewDecision decision;try{decision=ReviewDecision.valueOf(text(n,"decision"));}catch(RuntimeException ex){throw bad();}
        List<Issue> issues=new ArrayList<>();
        for(JsonNode i:array(n,"issues",18)) {keys(i,"issueId","taskIds","constraintId","code","severity","evidenceRefs","suggestion");Severity severity;try{severity=Severity.valueOf(text(i,"severity"));}catch(RuntimeException ex){throw bad();}
            List<String> tasks=strings(i,"taskIds",9);if(tasks.isEmpty())throw bad();issues.add(new Issue(text(i,"issueId"),tasks,text(i,"constraintId"),text(i,"code"),severity,strings(i,"evidenceRefs",16),text(i,"suggestion")));}
        if((decision==ReviewDecision.PASS&&!issues.isEmpty())||(decision!=ReviewDecision.PASS&&issues.isEmpty()))throw bad();
        return new Review(decision,issues);
    }
    public JsonNode tree(Object value) {return mapper.valueToTree(value);}
    private JsonNode read(String raw) {if(raw==null||raw.length()>150000)throw bad();try{JsonNode n=mapper.readTree(raw);if(n==null)throw bad();return n;}catch(Exception ex){throw bad();}}
    private void keys(JsonNode n,String... names) {if(!n.isObject()||n.size()!=names.length)throw bad();for(String name:names)if(!n.has(name))throw bad();}
    private JsonNode array(JsonNode n,String field,int limit) {JsonNode a=n.get(field);if(a==null||!a.isArray()||a.size()>limit)throw bad();return a;}
    private List<String> strings(JsonNode n,String field,int limit) {List<String> values=new ArrayList<>();for(JsonNode v:array(n,field,limit)){if(!v.isTextual()||v.textValue().isBlank()||v.textValue().length()>1024)throw bad();values.add(v.textValue());}if(new HashSet<>(values).size()!=values.size())throw bad();return values;}
    private String text(JsonNode n,String field) {JsonNode v=n.get(field);if(v==null||!v.isTextual()||v.textValue().isBlank()||v.textValue().length()>1024)throw bad();return v.textValue();}
    private IllegalArgumentException bad() {return new IllegalArgumentException("Invalid planning role JSON contract");}
}
