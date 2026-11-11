package com.diet.service.meal;
import com.diet.service.meal.planning.runtime.MealPlanningChatService;
import com.diet.service.meal.planning.PlanningTypes.*;
import com.diet.model.*;
import com.diet.enums.SourceMode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningChatServiceTest {
    @Test void dateGroupedResultKeepsOnlyActualChosenMealsInExposureCards() {
        var meal=new Candidate(3L,SourceMode.PUBLIC,null,"鸡蛋面",List.of("晚餐"),List.of(),false,Map.of("healthGoal",List.of("清淡")),Map.of(),"hash");
        var result=new Result("p1",1,Status.PARTIAL,"RULE_FALLBACK",false,List.of(new Day(LocalDate.parse("2026-10-21"),List.of(new Block("d0-dinner","晚餐",meal,"标签：清淡",false),new Block("d1-dinner","晚餐",null,"",false)))),List.of(new Gap("d1-dinner","NO_MATCH")),List.of(),List.of("未完成语义复核"));
        var service=new MealPlanningChatService(i->Mono.just(result));
        var request=new ChatRequest("s1","未来三天只安排晚餐",SourceMode.PUBLIC,Map.of());
        assertTrue(service.matches(request));
        var response=service.plan(1L,request,SessionState.fresh("s1",1L,SourceMode.PUBLIC),"t1");
        assertEquals("PLAN",response.responseType());assertEquals(1,response.displayBlocks().size());
        assertEquals(3L,response.displayBlocks().get(0).id());assertEquals("p1",response.mealPlan().planId());
        assertFalse(service.matches(new ChatRequest("s1","鸡蛋怎么炒",SourceMode.PUBLIC,Map.of())));
        assertTrue(response.planBlocks().isEmpty());
    }
}
