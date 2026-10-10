package com.diet.service.meal.intake;

import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.*;
import com.diet.service.meal.*;
import com.diet.service.meal.constraint.*;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recipe.RecipeQueryService;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.*;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MealIntakeOrchestratorTest {
    @Test void intakePrecedesRecipeAndPersonalEmptyRoutesAndReturnsDraft() {
        var fixture = new Fixture();
        var draft = new MealDraft("d", "帮我录入番茄面", new MealDraftData("番茄面", List.of(), null,null,null,null,null,null,null),
                List.of("mealTime"), List.of(), Map.of(), MealDraftStatus.NEEDS_INPUT,null);
        var created = fixture.initial.withMealDraft(draft);
        when(fixture.workflow.handle(eq(7L), any(), eq(fixture.initial)))
                .thenReturn(Optional.of(new MealIntakeWorkflow.Result(created,"请补充餐次。",false)));
        when(fixture.recipes.matches(anyString(),any())).thenReturn(true);
        var result = fixture.orchestrator.dietChat(7L,new ChatRequest("s","帮我录入番茄面，做法是煮面",SourceMode.PERSONAL,Map.of()));
        assertThat(result.responseType()).isEqualTo("MEAL_DRAFT");
        assertThat(result.mealDraft()).isEqualTo(draft);
        assertThat(result.missingSlots()).containsExactly("mealTime");
        verify(fixture.states).save(created);
        verifyNoInteractions(fixture.recipes,fixture.meals,fixture.intent);
    }

    @Test void lockReloadUsesCurrentStateAndDoesNotOverwriteTransactionalReceipt() {
        var fixture = new Fixture();
        var latest = fixture.initial.withMealDraft(new MealDraft("latest","番茄面 午餐",
                new MealDraftData("番茄面",List.of("午餐"),null,null,null,null,null,null,null),
                List.of(),List.of(),Map.of(),MealDraftStatus.READY,null));
        var saved = latest.withMealDraft(latest.mealDraft().saved(99L));
        when(fixture.states.loadExisting("s",7L,SourceMode.PERSONAL)).thenReturn(fixture.initial,latest);
        when(fixture.workflow.handle(eq(7L), any(), eq(latest)))
                .thenReturn(Optional.of(new MealIntakeWorkflow.Result(saved,"已保存，餐食 ID：99。",true)));
        var result = fixture.orchestrator.dietChat(7L,new ChatRequest("s","确认保存",SourceMode.PERSONAL,
                Map.of("mealDraftAction",Map.of("draftId","latest","action","SAVE"))));
        assertThat(result.mealDraft().savedMealId()).isEqualTo(99L);
        verify(fixture.states,never()).save(any());
        verify(fixture.workflow).handle(eq(7L),any(),eq(latest));
    }

    @Test void malformedActionAndForeignSessionAreRejectedBeforeAnyCreationOrModel() {
        var fixture = new Fixture();
        assertThatThrownBy(() -> fixture.orchestrator.dietChat(7L,new ChatRequest("s","确认保存",SourceMode.PERSONAL,
                Map.of("mealDraftAction",Map.of("action","SAVE")))))
                .hasMessageContaining("草稿操作无效");
        assertThatThrownBy(() -> fixture.orchestrator.dietChat(7L,new ChatRequest("other","确认保存",SourceMode.PERSONAL,
                Map.of("mealDraftAction",Map.of("draftId","d","action","SAVE")))))
                .hasMessageContaining("不属于当前用户");
        verify(fixture.states,never()).loadOrCreate(any(),any(),any());
        verifyNoInteractions(fixture.workflow,fixture.intent,fixture.meals);
    }

    private static class Fixture {
        final SessionService sessions = mock(SessionService.class);
        final SessionStateService states = mock(SessionStateService.class);
        final IntentAgentService intent = mock(IntentAgentService.class);
        final MealService meals = mock(MealService.class);
        final AgentTraceService trace = mock(AgentTraceService.class);
        final MealIntakeWorkflow workflow = mock(MealIntakeWorkflow.class);
        final RecipeQueryService recipes = mock(RecipeQueryService.class);
        final SessionState initial = SessionState.fresh("s",7L,SourceMode.PERSONAL);
        final DietOrchestratorService orchestrator;
        Fixture() {
            when(states.loadOrCreate("s",7L,SourceMode.PERSONAL)).thenReturn(initial);
            orchestrator = new DietOrchestratorService(sessions,states,intent,mock(IntentReviseService.class),
                    new SlotMergeService(),mock(ClarifyAgentService.class),mock(MealSearchService.class),
                    mock(MealRankService.class),mock(MealPlanService.class),mock(RecommendResponseAgentService.class),
                    meals,mock(RiskGuardService.class),trace,new ConstraintParser(new IngredientCatalog()));
            orchestrator.setMealIntakeWorkflow(workflow);
            orchestrator.setRecipeQueryService(recipes);
        }
    }
}
