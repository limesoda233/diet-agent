package com.diet.service.cooking;

import com.diet.enums.*;
import com.diet.model.*;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.*;
import com.diet.service.meal.*;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.retrieval.*;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.*;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CookingOrchestratorTest {
    @Test void servesPublicCookingWithoutRequiringPersonalMealsAndPreservesMealIdsAndPendingRestrictions() {
        var sessions=mock(SessionService.class); var states=mock(SessionStateService.class); var trace=mock(AgentTraceService.class);
        var intent=mock(IntentAgentService.class); var meals=mock(MealService.class); var retrieval=mock(MealRetrievalService.class);
        var state=SessionState.fresh("s",1L,SourceMode.PERSONAL).appendLastRecommendations(List.of(7L));
        when(states.loadOrCreate("s",1L,SourceMode.PERSONAL)).thenReturn(state);
        var service=new DietOrchestratorService(sessions,states,intent,mock(IntentReviseService.class),new SlotMergeService(),mock(ClarifyAgentService.class),
                mock(MealSearchService.class),mock(MealRankService.class),mock(MealPlanService.class),mock(RecommendResponseAgentService.class),meals,mock(RiskGuardService.class),trace,retrieval,new ConstraintParser(new IngredientCatalog()));
        var props=new CookingProperties(); props.setEnabled(true);
        service.setCookingService(new CookingService(new LocalRecipeProvider(),props,trace));
        var response=service.dietChat(1L,new ChatRequest("s","番茄炒蛋怎么做",SourceMode.PERSONAL,Map.of()));
        assertThat(response.speechText()).contains("recipe_01","LOCAL_EXAMPLE");
        assertThat(response.displayBlocks()).isEmpty();
        var saved=ArgumentCaptor.forClass(SessionState.class); verify(states).save(saved.capture());
        assertThat(saved.getValue().lastRecommendations()).containsExactly(7L);
        verifyNoInteractions(intent,meals,retrieval);
        when(states.loadOrCreate("s",1L,SourceMode.PERSONAL)).thenReturn(state.withRetrieval(RetrievalContext.empty().pending("必须含西虹柿")));
        assertThat(service.dietChat(1L,new ChatRequest("s","番茄炒蛋怎么做",SourceMode.PERSONAL,Map.of())).responseType()).isEqualTo("CLARIFY");
    }
}
