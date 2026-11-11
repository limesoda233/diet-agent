package com.diet.service.meal.planning;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.diet.service.meal.planning.PlanningTypes.*;
import static com.diet.service.meal.planning.PlanningPorts.*;

class MealPlanningCoordinatorTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-10-20T17:00:00Z"),ZoneOffset.UTC);
    @Test void returnsDatesAndPreservesOnlyRequestedMeals() {
        Fake f=new Fake();var result=coordinator(f).plan(PlanningRequestParserTest.input("未来三天只安排晚餐",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.COMPLETE);assertThat(result.days()).hasSize(3);
        assertThat(result.days().get(0).date()).isEqualTo(LocalDate.of(2026,10,21));
        assertThat(result.days()).allSatisfy(d->assertThat(d.blocks()).hasSize(1));
        assertThat(result.planId()).isEqualTo("saved-plan");assertThat(f.calls.get()).isEqualTo(3);
    }
    @Test void usesMatchingFallbackAndExpansionWithoutRelaxingHardRepeat() {
        Fake f=new Fake();f.failMenu=true;f.scarce=true;
        var result=coordinator(f).plan(PlanningRequestParserTest.input("未来三天晚餐不要重复",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.PARTIAL);
        assertThat(result.strategy()).contains("DETERMINISTIC");assertThat(result.gaps()).hasSize(2);
        assertThat(result.days().stream().flatMap(d->d.blocks().stream()).filter(b->b.selectedMeal()!=null).map(b->b.selectedMeal().mealId()).toList()).doesNotHaveDuplicates();
        assertThat(f.limits).contains(5,10);assertThat(result.warnings()).contains("CANDIDATES_EXPANDED");
    }
    @Test void menuInventedIdCannotEscapeFallbackAndAllFailuresCannotBeComplete() {
        Fake f=new Fake();f.inventMenu=true;
        var result=coordinator(f).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(result.days().get(0).blocks().get(0).selectedMeal().mealId()).isEqualTo(1);
        assertThat(result.strategy()).contains("DETERMINISTIC");
        Fake failed=new Fake();failed.failedRetrieval=true;
        var unavailable=coordinator(failed).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(unavailable.status()).isEqualTo(Status.UNAVAILABLE);assertThat(failed.calls.get()).isEqualTo(1);
    }
    @Test void reviewTimeoutExplicitlyDegradesButKnownUnresolvedOmissionRemainsPartial() {
        Fake f=new Fake();f.reviewTimeout=true;
        var degraded=coordinator(f).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(degraded.status()).isEqualTo(Status.DEGRADED);assertThat(degraded.reviewed()).isFalse();
        Fake unresolved=new Fake();unresolved.omissionThenTimeout=true;
        var partial=coordinator(unresolved).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(partial.status()).isEqualTo(Status.PARTIAL);assertThat(partial.gaps()).extracting(Gap::code).contains("REVIEW_UNRESOLVED");
        assertThat(unresolved.calls.get()).isEqualTo(5);
    }
    @Test void contextChangeReturnsNoOldCardsAndDoesNotPersist() {
        Fake f=new Fake();f.changed=true;
        var result=coordinator(f).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.NEEDS_INPUT);assertThat(result.days()).isEmpty();assertThat(result.warnings()).contains("CONTEXT_CHANGED");assertThat(f.saved).isFalse();
    }
    @Test void blockedAndUnsupportedContextCannotCallModelsOrRetrieve() {
        Fake f=new Fake();f.context=new FrozenContext("e",Map.of(),com.diet.service.meal.constraint.MealConstraints.empty(),List.of(),List.of("精确热量"),List.of());
        var result=coordinator(f).plan(PlanningRequestParserTest.input("今天晚餐控制500卡",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.NEEDS_INPUT);assertThat(result.unsupportedConstraints()).contains("精确热量");assertThat(f.calls.get()).isZero();assertThat(f.limits).isEmpty();
    }
    @Test void plannerCannotChangeScopeAndSoftRepeatsAreMarked() {
        Fake f=new Fake();f.invalidPlanner=true;f.scarce=true;
        var result=coordinator(f).plan(PlanningRequestParserTest.input("未来两天晚餐尽量不重复",Map.of())).block();
        assertThat(result.days()).hasSize(2);assertThat(result.days().stream().flatMap(d->d.blocks().stream()).anyMatch(Block::repeated)).isTrue();
        assertThat(result.warnings()).contains("PLANNER_FALLBACK");
    }
    @Test void malformedUnscopedReviewCannotInventANewHardRequirement() {
        Fake f=new Fake();f.inventReview=true;
        var result=coordinator(f).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.DEGRADED);
        assertThat(result.reviewed()).isFalse();
        assertThat(result.warnings()).contains("REVIEW_UNAVAILABLE");
    }
    @Test void retrievalRunsWithAtMostThreeRequestsInFlight() {
        Fake f=new Fake();f.slowRetrieval=true;
        var result=coordinator(f).plan(PlanningRequestParserTest.input("未来三天三餐",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.PARTIAL); // dinner-only frozen fixture cannot fill breakfast/lunch
        assertThat(f.peak.get()).isBetween(2,3);
    }
    @Test void totalDeadlineDoesNotLaunchMenuReviewOrPersistenceAfterCancelledRetrieval() {
        Fake f=new Fake();f.neverRetrieve=true;
        var b=new Budget(Duration.ofMillis(60),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),3);
        var result=new MealPlanningCoordinator(f,f,f,f,f,clock,b).plan(PlanningRequestParserTest.input("今天晚餐",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.UNAVAILABLE);assertThat(result.days()).isEmpty();
        assertThat(f.calls.get()).isEqualTo(1);assertThat(f.saved).isFalse();
    }
    @Test void failedTaskRetrievalKeepsItsFailureCodeEvenWhenMenuClaimsNoMatch() {
        List<JsonNode> roleInputs=new ArrayList<>();
        Fake f=new Fake() {
            @Override public Mono<Pool> fetch(Input input,FrozenContext context,Task task,int limit,Duration duration) {
                if(task.dayOffset()==1)return Mono.just(new Pool(List.of(),true,false));
                return super.fetch(input,context,task,limit,duration);
            }
            @Override public Mono<String> call(Role role,JsonNode data,Duration duration) {
                if(role==Role.MENU||role==Role.REVIEW)roleInputs.add(data);
                if(role!=Role.MENU)return super.call(role,data,duration);
                calls.incrementAndGet();
                return Mono.just("{\"assignments\":[{\"taskId\":\"d0-dinner\",\"mealId\":1,\"reasonFactRefs\":[]},{\"taskId\":\"d1-dinner\",\"mealId\":null,\"reasonFactRefs\":[]}],\"gaps\":[{\"taskId\":\"d1-dinner\",\"code\":\"NO_MATCH\"}]}");
            }
        };
        var result=coordinator(f).plan(PlanningRequestParserTest.input("未来两天晚餐",Map.of())).block();
        assertThat(result.status()).isEqualTo(Status.PARTIAL);assertThat(result.strategy()).isEqualTo("MULTI_AGENT");
        assertThat(result.gaps()).containsExactly(new Gap("d1-dinner","RETRIEVAL_FAILED"));
        assertThat(roleInputs).hasSize(2).allSatisfy(data->assertThat(data.path("taskRetrievalStatus").path("d1-dinner").asText()).isEqualTo("RETRIEVAL_FAILED"));
    }
    MealPlanningCoordinator coordinator(Fake f) {
        Budget budget=new Budget(Duration.ofSeconds(3),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(25),3);
        return new MealPlanningCoordinator(f,f,f,f,f,clock,budget);
    }
    static class Fake implements ContextPort,CandidatePort,RolePort,RepositoryPort,Events {
        FrozenContext context=PlanningSafetyTest.CONTEXT;final AtomicInteger calls=new AtomicInteger();final List<Integer> limits=Collections.synchronizedList(new ArrayList<>());
        boolean failMenu,scarce,inventMenu,failedRetrieval,reviewTimeout,omissionThenTimeout,changed,saved,invalidPlanner,inventReview,slowRetrieval,neverRetrieve;int reviews;
        final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
        final PlanningJsonCodec json=new PlanningJsonCodec();
        public Mono<FrozenContext> freeze(Input i,Duration d){
            if(omissionThenTimeout)context=new FrozenContext(context.memoryEpoch(),context.memoryItemVersions(),context.hardConstraints(),List.of(new Preference("current-0","cuisine","家常菜",List.of("d0-dinner"),"CURRENT_MESSAGE")),context.unsupportedConstraints(),context.blockers());
            return Mono.just(context);
        }
        public Mono<Boolean> unchanged(Input i,FrozenContext c,Duration d){return Mono.just(!changed);}
        public Mono<Boolean> unchanged(Input i,FrozenContext c,Snapshot s,Duration d){return Mono.just(true);}
        public Mono<Pool> fetch(Input i,FrozenContext c,Task t,int limit,Duration d){
            limits.add(limit);if(neverRetrieve)return Mono.never();
            var pool=new Pool(failedRetrieval?List.of():List.of(PlanningSafetyTest.meal(scarce?1:t.dayOffset()+1)),failedRetrieval,false);
            if(!slowRetrieval)return Mono.just(pool);
            return Mono.defer(()->{peak.accumulateAndGet(active.incrementAndGet(),Math::max);return Mono.delay(Duration.ofMillis(10)).map(v->{active.decrementAndGet();return pool;});});
        }
        public Mono<String> call(Role role,JsonNode data,Duration d) {
            calls.incrementAndGet();
            if(role==Role.PLANNER)return Mono.just(invalidPlanner?"{\"tasks\":[],\"clarifications\":[]}":json.tree(Map.of("tasks",data.get("tasks"),"clarifications",List.of())).toString());
            if(role==Role.MENU) {
                if(failMenu)return Mono.error(new IllegalStateException("menu unavailable"));
                List<Map<String,Object>> assignments=new ArrayList<>();
                for(JsonNode t:data.get("tasks")){String id=t.get("taskId").asText();var row=new LinkedHashMap<String,Object>();row.put("taskId",id);row.put("mealId",inventMenu?999:data.get("candidatePools").get(id).get(0).asLong());row.put("reasonFactRefs",List.of());assignments.add(row);}
                return Mono.just(json.tree(Map.of("assignments",assignments,"gaps",List.of())).toString());
            }
            reviews++;
            if(reviewTimeout||(omissionThenTimeout&&reviews>1))return Mono.never();
            if(inventReview)return Mono.just("{\"decision\":\"NEEDS_INPUT\",\"issues\":[{\"issueId\":\"fake\",\"taskIds\":[\"d0-dinner\"],\"constraintId\":\"requiredIngredients\",\"code\":\"FAKE\",\"severity\":\"HARD\",\"evidenceRefs\":[\"constraint:requiredIngredients\"],\"suggestion\":\"强制吃鱼\"}]}");
            if(omissionThenTimeout)return Mono.just("{\"decision\":\"REVISE\",\"issues\":[{\"issueId\":\"i1\",\"taskIds\":[\"d0-dinner\"],\"constraintId\":\"current-0\",\"code\":\"OMISSION\",\"severity\":\"SOFT\",\"evidenceRefs\":[\"preference:current-0\"],\"suggestion\":\"补充需求说明\"}]}");
            return Mono.just("{\"decision\":\"PASS\",\"issues\":[]}");
        }
        public Mono<PlanIdentity> save(Input i,FrozenContext c,Spec p,Snapshot s,Result r){saved=true;return Mono.just(new PlanIdentity("saved-plan",1));}
        public void emit(Input i,String e,Map<String,Object> d){}
    }
}
