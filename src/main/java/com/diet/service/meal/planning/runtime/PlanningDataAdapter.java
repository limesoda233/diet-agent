package com.diet.service.meal.planning.runtime;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.meal.*;
import com.diet.service.meal.constraint.*;
import com.diet.service.meal.planning.PlanningPorts.*;
import com.diet.service.memory.*;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static com.diet.service.meal.planning.PlanningTypes.*;

/** Read-only MySQL adapter. No call below fetches memory except freeze/final recheck. */
public class PlanningDataAdapter implements ContextPort,CandidatePort {
    private final MealService meals;private final MemoryStore memories;private final MemoryPolicy policy;
    private final IngredientCatalog catalog;private final Scheduler scheduler;private final AgentTraceService trace;
    private final ObjectMapper json;private final Clock clock;private final Map<String,List<String>> slotValues=new LinkedHashMap<>();
    public PlanningDataAdapter(MealService meals,MemoryStore memories,MemoryPolicy policy,IngredientCatalog catalog,Scheduler scheduler,AgentTraceService trace,ObjectMapper json,Clock clock) {
        this.meals=meals;this.memories=memories;this.policy=policy;this.catalog=catalog;this.scheduler=scheduler;this.trace=trace;this.json=json;this.clock=clock;
        try(var stream=getClass().getResourceAsStream("/diet/retrieval/catalog.json")) {json.readTree(stream).path("slots").fields().forEachRemaining(e->{var values=new ArrayList<String>();e.getValue().forEach(v->values.add(v.asText()));slotValues.put(e.getKey(),List.copyOf(values));});}
        catch(Exception e){throw new IllegalStateException("规划标签字典不可用",e);}
    }
    private <T> Mono<T> read(Supplier<T> work,Duration timeout) {
        Supplier<T> propagated=trace==null?work:trace.propagate(work);
        return Mono.fromSupplier(propagated).subscribeOn(scheduler).timeout(timeout);
    }
    private PlanningMemorySnapshot memory(long user) {return policy==null?new PlanningMemorySnapshot(0,List.of()):memories.planningSnapshot(user);}
    @Override public Mono<FrozenContext> freeze(Input input,Duration timeout) {
        return read(()->{
            var snapshot=memory(input.userId());var blockers=new ArrayList<String>();var hard=input.constraints();
            if(input.message().contains("过敏"))blockers.add(MemoryPolicy.ALLERGY_LIMIT);
            try{if(policy!=null)hard=policy.mealConstraints(snapshot.items(),hard);catalog.validate(hard);}catch(IllegalArgumentException invalid){blockers.add(invalid.getMessage());}
            var prefs=new ArrayList<Preference>();
            int requestedDays=new com.diet.service.meal.planning.MealPlanningRequestParser(clock).parse(input).days();
            for(String segment:input.message().split("[，,；;。]"))for(String dimension:List.of("healthGoal","cuisine","taste","convenience","mood","scene"))for(String value:slotValues.getOrDefault(dimension,List.of()))
                if(segment.contains(value)&&!segment.matches("(?s).*(不要|不吃|不喜欢|讨厌|避免|不想).*"+java.util.regex.Pattern.quote(value)+".*"))
                    prefs.add(new Preference("current-"+prefs.size(),dimension,value,localTasks(segment,requestedDays),"CURRENT_MESSAGE"));
            var s=input.slots();var session=Map.of("healthGoal",s.healthGoal(),"cuisine",s.cuisine(),"taste",s.taste(),"convenience",s.convenience(),"mood",s.mood(),"scene",s.scene());
            session.forEach((dimension,values)->{if(!hasPreference(prefs,dimension))for(String value:values)if(catalog.supportsSlot(dimension,value))prefs.add(new Preference("session-"+prefs.size(),dimension,value,List.of(),"SESSION"));});
            for(var item:snapshot.items())if("ACTIVE".equals(item.status())&&Set.of("TASTE","CUISINE").contains(item.type())&&Set.of("LIKE","DISLIKE").contains(item.stance())) {
                String dimension="CUISINE".equals(item.type())?"cuisine":catalog.supportsSlot("taste",item.value())?"taste":"healthGoal";
                if(catalog.supportsSlot(dimension,item.value()) && !hasPreference(prefs,dimension))
                    prefs.add(new Preference("memory-"+item.id(),dimension,item.value(),List.of(),item.sourceType()+"_MEMORY:"+item.stance()));
            }
            var versions=new TreeMap<String,Long>();snapshot.items().forEach(i->versions.put(i.id(),i.version()));
            var unsupported=new ArrayList<String>();
            if(unsupported(input.message()) || snapshot.items().stream().anyMatch(i->"ACTIVE".equals(i.status())&&"EXPLICIT".equals(i.sourceType())&&Set.of("EQUIPMENT","MAX_MINUTES").contains(i.type())))unsupported.add("当前餐食数据不能核验价格、营养数值、烹饪时长或用具；请明确放宽这些条件后重新规划");
            return new FrozenContext(fingerprint(snapshot),versions,hard,prefs,unsupported,blockers);
        },timeout);
    }
    private boolean hasPreference(List<Preference> prefs,String dimension) {
        return prefs.stream().anyMatch(p->(p.dimension().equals(dimension)||Set.of("taste","healthGoal").contains(p.dimension())&&Set.of("taste","healthGoal").contains(dimension))&&(p.sourceRef().startsWith("CURRENT")||p.sourceRef().equals("SESSION")));
    }
    private boolean unsupported(String text) {
        String value=text.toLowerCase(Locale.ROOT);
        return java.util.regex.Pattern.compile("预算|卡路里|千卡|大卡|热量|营养|蛋白质|脂肪|碳水|耗时|时长|分钟|半小时|小时内|一刻钟|电饭[煲锅]|空气炸锅|微波炉|烤箱|炒锅|蒸锅|高压锅|锅具|厨具|只用.{0,10}[锅炉]|不用.{0,10}[锅炉]|[0-9一二两三四五六七八九十百]+(?:\\.[0-9]+)?\\s*(?:kcal|cal|kj|kg|mg|g|卡|千焦|克|千克|元|块)").matcher(value).find();
    }
    private List<String> localTasks(String segment,int requestedDays) {
        List<Integer> days=segment.matches("(?s).*后[两二2]天.*")?List.of(Math.max(0,requestedDays-2),requestedDays-1):segment.matches("(?s).*前[两二2]天.*")?List.of(0,1):
                segment.matches("(?s).*第[一1]天.*")?List.of(0):segment.matches("(?s).*第[二2]天.*")?List.of(1):segment.matches("(?s).*第[三3]天.*")?List.of(2):List.of();
        return days.stream().flatMap(d->List.of("breakfast","lunch","dinner").stream().map(m->"d"+d+"-"+m)).toList();
    }
    @Override public Mono<Boolean> unchanged(Input input,FrozenContext context,Duration timeout) {return read(()->context.memoryEpoch().equals(fingerprint(memory(input.userId()))),timeout);}
    @Override public Mono<Pool> fetch(Input input,FrozenContext context,Task task,int limit,Duration timeout) {
        return read(()->{
            // Soft preferences rank the same hard-valid pool; they do not silently filter it out.
            var query=new SlotBundle(List.of(task.mealTime()),null,null,null,null,null,null);
            var retrieved=meals.searchFrozen(input.sourceMode(),input.userId(),query,context.hardConstraints());
            var valid=retrieved.stream().filter(Objects::nonNull).map(this::candidate).filter(c->eligible(input,context,task,c))
                    .sorted(Comparator.comparingDouble((Candidate c)->preferenceScore(context,task,c)).reversed().thenComparingLong(Candidate::mealId)).toList();
            return new Pool(valid.stream().limit(limit).toList(),false,retrieved.size()>=50||valid.size()>limit);
        },timeout);
    }
    private double preferenceScore(FrozenContext context,Task task,Candidate c) {
        double score=0;for(var p:context.preferences())if((p.taskIds().isEmpty()||p.taskIds().contains(task.taskId()))&&c.tags().getOrDefault(p.dimension(),List.of()).contains(p.value()))
            score+=(p.sourceRef().contains("DISLIKE")?-1:1)*(p.sourceRef().startsWith("CURRENT")?1:p.sourceRef().equals("SESSION")?.1:p.sourceRef().startsWith("INFERRED")?.02:.05);
        return score;
    }
    private boolean eligible(Input input,FrozenContext context,Task task,Candidate c) {
        if(c.sourceMode()!=input.sourceMode() || (input.sourceMode()==SourceMode.PERSONAL?!input.userId().equals(c.ownerUserId()):c.ownerUserId()!=null)||!c.mealTimes().contains(task.mealTime()))return false;
        var hard=context.hardConstraints();
        if((!hard.requiredIngredients().isEmpty()||!hard.excludedIngredients().isEmpty())&&!c.ingredientsKnown())return false;
        if(!c.ingredients().containsAll(hard.requiredIngredients())||!Collections.disjoint(c.ingredients(),hard.excludedIngredients()))return false;
        for(var e:hard.requiredSlots().entrySet())if(Collections.disjoint(c.tags().getOrDefault(e.getKey(),List.of()),e.getValue()))return false;
        for(var e:hard.excludedSlots().entrySet())if(!Collections.disjoint(c.tags().getOrDefault(e.getKey(),List.of()),e.getValue()))return false;
        return true;
    }
    private Candidate candidate(MealItem meal) {
        var s=meal.slots();var tags=new LinkedHashMap<String,List<String>>();
        tags.put("mealTime",s.mealTime());tags.put("mood",s.mood());tags.put("scene",s.scene());tags.put("healthGoal",s.healthGoal());tags.put("cuisine",s.cuisine());tags.put("taste",s.taste());tags.put("convenience",s.convenience());
        var ingredients=meal.knowledge()==null?List.<String>of():meal.knowledge().ingredients();var facts=new LinkedHashMap<String,String>();
        tags.forEach((field,values)->values.forEach(value->facts.put("meal:"+meal.id()+":"+field+":"+value,value)));
        ingredients.forEach(value->facts.put("meal:"+meal.id()+":ingredient:"+value,value));
        var c=new Candidate(meal.id(),meal.sourceType(),meal.ownerUserId(),meal.name(),s.mealTime(),ingredients,!ingredients.isEmpty(),tags,facts,"");
        return new Candidate(c.mealId(),c.sourceMode(),c.ownerUserId(),c.name(),c.mealTimes(),c.ingredients(),c.ingredientsKnown(),c.tags(),c.facts(),fingerprint(c));
    }
    @Override public Mono<Boolean> unchanged(Input input,FrozenContext context,Snapshot snapshot,Duration timeout) {
        return read(()->{
            var frozen=new LinkedHashMap<Long,String>();snapshot.pools().values().forEach(p->p.candidates().forEach(c->frozen.put(c.mealId(),c.fingerprint())));
            var fresh=new HashMap<Long,String>();meals.findPlanningMeals(input.sourceMode(),input.userId(),List.copyOf(frozen.keySet())).forEach(m->{var c=candidate(m);fresh.put(c.mealId(),c.fingerprint());});
            return frozen.equals(fresh);
        },timeout);
    }
    private String fingerprint(Object data) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(data).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("规划快照无法编码",e);}
    }
}
