package com.diet.service.meal;
import com.diet.enums.*;
import com.diet.model.*;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.*;
import com.diet.service.meal.constraint.*;
import com.diet.service.meal.planning.runtime.MealPlanningChatService;
import com.diet.service.meal.planning.PlanningTypes.*;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recipe.RecipeQueryService;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.*;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningOrchestratorTest {
    @Test void directPlanningCannotBypassTheExistingHealthRiskPolicy() {
        var states=mock(SessionStateService.class);
        when(states.loadOrCreate("s1",1L,SourceMode.PUBLIC)).thenReturn(SessionState.fresh("s1",1L,SourceMode.PUBLIC));
        var orchestrator=new DietOrchestratorService(mock(SessionService.class),states,mock(IntentAgentService.class),new IntentReviseService(),new SlotMergeService(),mock(ClarifyAgentService.class),mock(MealSearchService.class),new MealRankService(),mock(MealPlanService.class),mock(RecommendResponseAgentService.class),mock(MealService.class),new RiskGuardService(),mock(AgentTraceService.class),new ConstraintParser(new IngredientCatalog()));
        orchestrator.setMealPlanningChatService(new MealPlanningChatService(input->{throw new AssertionError("健康风险不应进入规划模型");}));
        var result=orchestrator.dietChat(1L,new ChatRequest("s1","我是糖尿病患者，安排今天晚餐",SourceMode.PUBLIC,Map.of()));
        assertEquals("ANSWER",result.responseType());assertNull(result.mealPlan());assertTrue(result.displayBlocks().isEmpty());
        assertTrue(result.speechText().contains("健康"));
    }
    @Test void explicitPlanningPrecedesRecipeAndPreservesIngredientRestrictionsAndSource() {
        var states=mock(SessionStateService.class);var sessions=mock(SessionService.class);
        var state=SessionState.fresh("s1",1L,SourceMode.PUBLIC);
        when(states.loadOrCreate("s1",1L,SourceMode.PUBLIC)).thenReturn(state);
        var recipes=mock(RecipeQueryService.class);var intent=mock(IntentAgentService.class);
        var orchestrator=new DietOrchestratorService(sessions,states,intent,mock(IntentReviseService.class),new SlotMergeService(),mock(ClarifyAgentService.class),mock(MealSearchService.class),new MealRankService(),mock(MealPlanService.class),mock(RecommendResponseAgentService.class),mock(MealService.class),mock(RiskGuardService.class),mock(AgentTraceService.class),new ConstraintParser(new IngredientCatalog()));
        orchestrator.setRecipeQueryService(recipes);
        orchestrator.setMealPlanningChatService(new MealPlanningChatService(input->{
            assertEquals(1L,input.userId());assertEquals(SourceMode.PUBLIC,input.sourceMode());
            assertEquals(List.of("花生"),input.constraints().excludedIngredients());
            return Mono.just(new Result("p",1,Status.PARTIAL,"MULTI_AGENT",true,List.of(),List.of(new Gap("d0-dinner","NO_MATCH")),List.of(),List.of()));
        }));
        var response=orchestrator.dietChat(1L,new ChatRequest("s1","未来三天只安排晚餐，不要重复，不要花生",SourceMode.PUBLIC,Map.of()));
        assertEquals("PLAN",response.responseType());assertEquals("p",response.mealPlan().planId());
        verifyNoInteractions(intent,recipes);
    }
}
