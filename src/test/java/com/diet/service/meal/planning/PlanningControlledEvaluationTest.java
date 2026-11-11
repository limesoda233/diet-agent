package com.diet.service.meal.planning;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.diet.service.meal.planning.PlanningTypes.*;

/** Controlled acceptance and stability, never a quality judge for its own online Review role. */
class PlanningControlledEvaluationTest {
    @Test void frozenDisjointScenarioGroupsStayLegalAcrossThreeRuns() throws Exception {
        ObjectMapper json=new ObjectMapper();Set<String> groups=new HashSet<>();int observations=0;
        for(String split:List.of("development","locked")) {
            JsonNode data=json.readTree(getClass().getResourceAsStream("/evaluation/planning/"+split+".json"));
            for(JsonNode row:data.path("cases")) {
                assertThat(groups.add(row.path("group").asText())).as("split leakage: %s",row.path("id")).isTrue();
                for(int repeat=0;repeat<3;repeat++) {
                    var fake=new FixtureFake();String scenario=row.path("scenario").asText();
                    switch(scenario) {
                        case "SCARCE_FALLBACK"->{fake.scarce=true;fake.failMenu=true;}
                        case "OMISSION_THEN_TIMEOUT"->fake.omissionThenTimeout=true;
                        case "CHANGED_CONTEXT"->fake.changed=true;
                        case "FAILED_RETRIEVAL"->fake.failedRetrieval=true;
                        case "REVIEW_TIMEOUT"->fake.reviewTimeout=true;
                        case "FOREIGN_OWNER"->fake.foreign=true;
                        case "UNSUPPORTED"->fake.context=new FrozenContext("e",Map.of(),com.diet.service.meal.constraint.MealConstraints.empty(),List.of(),List.of("价格无元数据"),List.of());
                        default->{}
                    }
                    var b=new Budget(Duration.ofSeconds(3),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(100),Duration.ofMillis(25),3);
                    var coordinator=new MealPlanningCoordinator(fake,fake,fake,fake,fake,Clock.fixed(Instant.parse("2026-10-20T17:00:00Z"),ZoneOffset.UTC),b);
                    var result=coordinator.plan(PlanningRequestParserTest.input(row.path("message").asText(),Map.of())).block();
                    assertThat(result.status().name()).as("%s observation %d",row.path("id"),repeat).isEqualTo(row.path("status").asText());
                    assertThat(result.days()).hasSize(row.path("days").asInt());
                    var selected=result.days().stream().flatMap(d->d.blocks().stream()).map(Block::selectedMeal).filter(Objects::nonNull).toList();
                    assertThat(selected).hasSizeGreaterThanOrEqualTo(row.path("minimumSelected").asInt());
                    assertThat(selected).allMatch(c->c.ownerUserId()==7L&&c.sourceMode()==com.diet.enums.SourceMode.PERSONAL&&c.mealId()>=1&&c.mealId()<=3);
                    if(scenario.equals("SCARCE_FALLBACK"))assertThat(selected.stream().map(Candidate::mealId).toList()).doesNotHaveDuplicates();
                    assertThat(fake.calls.get()).isLessThanOrEqualTo(5);observations++;
                }
            }
        }
        assertThat(observations).isEqualTo(24);assertThat(groups).hasSize(8);
    }
    static class FixtureFake extends MealPlanningCoordinatorTest.Fake {
        boolean foreign;
        @Override public Mono<Pool> fetch(Input i,FrozenContext c,Task t,int limit,Duration d) {
            if(!foreign)return super.fetch(i,c,t,limit,d);
            limits.add(limit);var meal=PlanningSafetyTest.meal(1);
            return Mono.just(new Pool(List.of(new Candidate(1,meal.sourceMode(),99L,meal.name(),meal.mealTimes(),meal.ingredients(),meal.ingredientsKnown(),meal.tags(),meal.facts(),meal.fingerprint())),false,false));
        }
    }
}
