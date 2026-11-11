package com.diet.service.memory;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.memory.extraction.MemoryCandidate;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.session.*;
import com.diet.service.intent.*;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.meal.*;
import com.diet.service.meal.constraint.*;
import com.diet.service.recipe.RecipeQueryService;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryRoutingTest extends MemoryDatabaseFixture {
    @Test void extractedTemporaryAvoidanceReachesMealSearchWithoutBecomingSessionState() {
        var sessions=mock(SessionService.class);var states=mock(SessionStateService.class);
        when(states.loadOrCreate("s1",1L,SourceMode.PUBLIC)).thenReturn(SessionState.fresh("s1",1L,SourceMode.PUBLIC));
        var intent=mock(IntentAgentService.class);var revise=mock(IntentReviseService.class);
        var result=new IntentResult(com.diet.enums.Intent.MEAL_RECOMMENDATION,
                new SlotBundle(List.of("晚餐"),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),.95);
        when(intent.recognize(anyString(),anyLong(),anyString(),any(),any())).thenReturn(result);
        when(revise.revise(any(),any(),anyString())).thenReturn(result);
        var clarify=mock(ClarifyAgentService.class);when(clarify.decide(anyString(),anyString(),any())).thenReturn(ClarifyResult.ready());
        var search=mock(MealSearchService.class);when(search.search(any())).thenReturn(List.of());
        var orchestrator=new DietOrchestratorService(sessions,states,intent,revise,new SlotMergeService(),clarify,search,
                new MealRankService(),mock(MealPlanService.class),mock(RecommendResponseAgentService.class),mock(MealService.class),
                mock(RiskGuardService.class),mock(AgentTraceService.class),new ConstraintParser(new IngredientCatalog()));
        orchestrator.setMemoryService(new MemoryService(store,m->List.of(new MemoryCandidate("AVOID_INGREDIENT","花生","SELF","NEUTRAL","TEMPORARY","ADD","今天我避开花生"))));
        orchestrator.dietChat(1L,new ChatRequest("s1","今天我避开花生，推荐晚餐",SourceMode.PUBLIC,Map.of()));
        verify(search).search(argThat(r->r.constraints().excludedIngredients().equals(List.of("花生"))));
        verify(states).save(argThat(s->s.mealConstraintContext().constraints().excludedIngredients().isEmpty()));
        assertThat(store.list(1L)).isEmpty();
    }
    @Test void allergyStopsExplicitRecipeBeforeAnyRecipeOrIntentCall() {
        var sessions=mock(SessionService.class);
        var states=mock(SessionStateService.class);
        var intent=mock(IntentAgentService.class);
        var meals=mock(MealService.class);
        var recipes=mock(RecipeQueryService.class);
        when(states.loadOrCreate("s1",1L,SourceMode.PUBLIC)).thenReturn(SessionState.fresh("s1",1L,SourceMode.PUBLIC));
        var orchestrator=new DietOrchestratorService(sessions,states,intent,mock(IntentReviseService.class),
                new SlotMergeService(),mock(ClarifyAgentService.class),mock(MealSearchService.class),mock(MealRankService.class),
                mock(MealPlanService.class),mock(RecommendResponseAgentService.class),meals,mock(RiskGuardService.class),mock(AgentTraceService.class),new ConstraintParser(new IngredientCatalog()));
        orchestrator.setRecipeQueryService(recipes);
        var service=new MemoryService(store,m->List.of(new MemoryCandidate("ALLERGEN","花生","SELF","NEUTRAL","LONG_TERM","ADD","我对花生过敏")));
        orchestrator.setMemoryService(service);
        var response=orchestrator.dietChat(1L,new ChatRequest("s1","我对花生过敏，宫保鸡丁怎么做？",SourceMode.PUBLIC,Map.of()));
        assertThat(response.speechText()).contains("无法核验");
        assertThat(response.displayBlocks()).isEmpty();
        assertThat(response.memoryNotices()).anyMatch(t->t.contains("待确认"));
        verifyNoInteractions(recipes,intent,meals);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_messages WHERE role='user'",Integer.class)).isEqualTo(1);
    }
    @Test void preferencesOnlyFillMissingFieldsAndForbiddenIngredientsRemainHard() {
        var job=store.appendTurn(1L,"s1","我喜欢清淡，平时不吃香菜","t");
        store.remember(job,"TASTE","清淡","LIKE","EXPLICIT","我喜欢清淡",false);
        store.remember(job,"AVOID_INGREDIENT","香菜","NEUTRAL","EXPLICIT","平时不吃香菜",false);
        var policy=new MemoryPolicy(store);
        var empty=policy.defaults(1L,SlotBundle.empty());
        assertThat(empty.healthGoal()).containsExactly("清淡");
        var current=new SlotBundle(List.of(),List.of(),List.of(),List.of(),List.of(),List.of("辣"),List.of());
        assertThat(policy.defaults(1L,current).taste()).containsExactly("辣");
        assertThat(policy.mealConstraints(1L,MealConstraints.empty()).excludedIngredients()).contains("香菜");
        assertThatThrownBy(()->policy.mealConstraints(1L,new MealConstraints(List.of("香菜"),List.of(),Map.of(),Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
