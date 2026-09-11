package com.diet.service.retrieval;

import com.diet.enums.*;
import com.diet.model.*;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.*;
import com.diet.service.meal.*;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.*;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridOrchestratorTest {
    @Test void passesOriginalNeedAndRestrictionsToCoordinatorWithoutInvokingLegacyRank() {
        var sessions=mock(SessionService.class); var states=mock(SessionStateService.class);
        var intent=mock(IntentAgentService.class); var revise=mock(IntentReviseService.class); var clarify=mock(ClarifyAgentService.class);
        var search=mock(MealSearchService.class); var rank=mock(MealRankService.class); var retrieval=mock(MealRetrievalService.class);
        var generation=mock(RecommendResponseAgentService.class); var trace=mock(AgentTraceService.class);
        var state=SessionState.fresh("s",1L,SourceMode.PUBLIC);
        when(states.loadOrCreate("s",1L,SourceMode.PUBLIC)).thenReturn(state);
        var slots=new SlotBundle(List.of("晚餐"),null,null,null,null,null,null);
        var recognized=new IntentResult(Intent.MEAL_RECOMMENDATION,slots,1);
        when(intent.recognize(any(),any(),any(),any(),any())).thenReturn(recognized);
        when(revise.revise(any(),any(),any())).thenReturn(recognized);
        when(clarify.decide(any(),any(),any())).thenReturn(ClarifyResult.ready());
        when(retrieval.retrieve(any())).thenReturn(new MealRetrievalService.Result(List.of(),List.of(),List.of(),null));
        var service=new DietOrchestratorService(sessions,states,intent,revise,new SlotMergeService(),clarify,search,rank,mock(MealPlanService.class),generation,mock(MealService.class),mock(RiskGuardService.class),trace,retrieval,new ConstraintParser(new IngredientCatalog()));
        service.dietChat(1L,new ChatRequest("s","晚餐不要牛肉",SourceMode.PUBLIC,Map.of()));
        var capture=ArgumentCaptor.forClass(MealRetrievalRequest.class); verify(retrieval).retrieve(capture.capture());
        assertThat(capture.getValue().queryText()).contains("晚餐不要牛肉");
        assertThat(capture.getValue().constraints().excludedIngredients()).containsExactly("牛肉");
        verifyNoInteractions(search,rank,generation);
        when(retrieval.retrieve(any())).thenThrow(new IllegalArgumentException("明确限制需要启用检索知识库"));
        assertThatCode(()->service.dietChat(1L,new ChatRequest("s","晚餐不要牛肉",SourceMode.PUBLIC,Map.of()))).doesNotThrowAnyException();
        var saved=ArgumentCaptor.forClass(SessionState.class); verify(states,atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().retrieval().constraints().excludedIngredients()).containsExactly("牛肉");
    }
}
