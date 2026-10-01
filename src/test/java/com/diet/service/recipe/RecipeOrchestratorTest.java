package com.diet.service.recipe;

import com.diet.enums.Intent;
import com.diet.enums.SourceMode;
import com.diet.model.ChatRequest;
import com.diet.model.SessionState;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.IntentAgentService;
import com.diet.service.intent.IntentReviseService;
import com.diet.service.meal.MealPlanService;
import com.diet.service.meal.MealRankService;
import com.diet.service.meal.MealSearchService;
import com.diet.service.meal.MealService;
import com.diet.service.meal.constraint.ConstraintParser;
import com.diet.service.meal.constraint.IngredientCatalog;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.SessionService;
import com.diet.service.session.SessionStateService;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecipeOrchestratorTest {
    @Test
    void recipeRouteRunsBeforePersonalEmptyCheckAndIntentAgent() {
        var sessions = mock(SessionService.class);
        var states = mock(SessionStateService.class);
        var intent = mock(IntentAgentService.class);
        var meals = mock(MealService.class);
        var trace = mock(AgentTraceService.class);
        when(states.loadOrCreate("s", 7L, SourceMode.PERSONAL))
                .thenReturn(SessionState.fresh("s", 7L, SourceMode.PERSONAL));
        var orchestrator = new DietOrchestratorService(sessions, states, intent, mock(IntentReviseService.class),
                new SlotMergeService(), mock(ClarifyAgentService.class), mock(MealSearchService.class),
                mock(MealRankService.class), mock(MealPlanService.class), mock(RecommendResponseAgentService.class),
                meals, mock(RiskGuardService.class), trace, new ConstraintParser(new IngredientCatalog()));
        var recipes = mock(RecipeQueryService.class);
        when(recipes.matches("宫保鸡丁怎么做", Map.of())).thenReturn(true);
        when(recipes.answer("s", 7L, "宫保鸡丁怎么做", Map.of())).thenReturn(new RecipeAnswer(
                "宫保鸡丁做法 [S1]", List.of(new RecipeCitation("S1", "r1", "宫保鸡丁", "path.md", "commit", "hash")),
                false, Map.of()));
        orchestrator.setRecipeQueryService(recipes);

        var response = orchestrator.dietChat(7L,
                new ChatRequest("s", "宫保鸡丁怎么做", SourceMode.PERSONAL, Map.of()));

        assertThat(response.speechText()).contains("宫保鸡丁", "[S1]");
        verifyNoInteractions(intent, meals);
        verify(states).save(argThat(saved -> saved.currentIntent() == Intent.OTHER));
    }
}
