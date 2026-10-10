package com.diet.service.meal.intake;

import com.diet.enums.*;
import com.diet.model.*;
import com.diet.mapper.SessionMapper;
import com.diet.service.session.SessionStateService;
import com.diet.service.slot.SlotOptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MealIntakeWorkflowTest {
    ObjectMapper json = new ObjectMapper();
    Queue<String> outputs = new ArrayDeque<>();
    MealIntakeWorkflow workflow;
    SessionState state;
    @BeforeEach void setup() {
        SlotOptionService options = mock(SlotOptionService.class);
        when(options.findAllOptions()).thenReturn(Map.of("mealTime", List.of("早餐","午餐","晚餐"), "taste",List.of("微辣")));
        var extractor = new MealDraftService((system,user) -> Mono.just(outputs.remove()), options,json,new MealIntakeProperties(),null);
        workflow = new MealIntakeWorkflow(extractor,mock(MealDraftSaveService.class),null);
        state = SessionState.fresh("s",1L,SourceMode.PUBLIC);
    }
    ChatRequest request(String text) { return new ChatRequest("s",text,SourceMode.PUBLIC,Map.of()); }
    @Test void explicitIntakeWithPreparationGeneratesWithoutRecommendationContext() {
        outputs.add("{\"name\":{\"value\":\"番茄面\",\"evidence\":\"番茄面\"}}");
        var result = workflow.handle(1L,request("帮我录入番茄面，做法是煮面"),state).orElseThrow();
        assertThat(result.state().mealDraft().missingFields()).containsExactly("mealTime");
        assertThat(result.state().slots().mealTime()).isEmpty();
        assertThat(result.state().phase()).isEqualTo(SessionPhase.START);
    }
    @Test void ordinaryMessagesAndYesPassThrough() {
        for (String text : List.of("中午吃了宫保鸡丁","鸡肉能做什么菜","番茄面怎么做","是","好","如何录入系统日志"))
            assertThat(workflow.handle(1L,request(text),state)).isEmpty();
    }
    @Test void missingMealTimeCanBeCompletedAndExplicitEditPreservesName() {
        outputs.add("{\"name\":{\"value\":\"番茄面\",\"evidence\":\"番茄面\"}}");
        state = workflow.handle(1L,request("帮我录入番茄面"),state).orElseThrow().state();
        assertThat(workflow.handle(1L,request("鸡肉怎么做"),state)).isEmpty();
        outputs.add("{\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"中午\"}]}");
        state = workflow.handle(1L,request("中午"),state).orElseThrow().state();
        outputs.add("{\"mealTime\":[{\"value\":\"晚餐\",\"evidence\":\"晚餐\"}]}");
        state = workflow.handle(1L,request("把草稿餐次改成晚餐"),state).orElseThrow().state();
        assertThat(state.mealDraft().status()).isEqualTo(MealDraftStatus.READY);
        assertThat(state.mealDraft().data().name()).isEqualTo("番茄面");
        assertThat(state.mealDraft().data().mealTime()).containsExactly("晚餐");
        assertThat(workflow.handle(1L,request("好"),state)).isEmpty();
    }
    @Test void missingNameDoesNotConsumeUnrelatedShortReply() {
        outputs.add("{\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"中午\"}]}");
        state = workflow.handle(1L,request("帮我录入一道餐食，中午吃"),state).orElseThrow().state();
        assertThat(state.mealDraft().missingFields()).contains("name");
        assertThat(workflow.handle(1L,request("天气"),state)).isEmpty();
    }
    @Test void invalidActionsRejectWithoutExtractionOrSaveAndCancelIsTerminal() {
        outputs.add("{\"name\":{\"value\":\"番茄面\",\"evidence\":\"番茄面\"}}");
        state = workflow.handle(1L,request("帮我录入番茄面"),state).orElseThrow().state();
        for (Object action : List.of(Map.of("action","SAVE"),Map.of("draftId","old","action","SAVE"),Map.of("draftId",state.mealDraft().draftId(),"action","hack"))) {
            var result = workflow.handle(1L,new ChatRequest("s","确认保存",SourceMode.PUBLIC,Map.of("mealDraftAction",action)),state).orElseThrow();
            assertThat(result.state().mealDraft().status()).isEqualTo(MealDraftStatus.NEEDS_INPUT);
            assertThat(result.speechText()).contains("草稿");
        }
        var result = workflow.handle(1L,request("取消录入"),state).orElseThrow();
        assertThat(result.state().mealDraft().status()).isEqualTo(MealDraftStatus.CANCELLED);
        assertThat(workflow.handle(1L,request("确认保存"),result.state()).orElseThrow().speechText()).contains("取消");
    }
    @Test void foreignUserCannotUseDraft() {
        var result = workflow.handle(2L,new ChatRequest("s","确认保存",SourceMode.PUBLIC,Map.of("mealDraftAction",Map.of("draftId","x","action","SAVE"))),state).orElseThrow();
        assertThat(result.speechText()).contains("用户");
    }
    @Test void explicitReferenceWithoutSelectionAsksNameWithoutGuessing() {
        var result = workflow.handle(1L,request("把这道菜加入我的餐食库"),state).orElseThrow();
        assertThat(result.speechText()).contains("菜名");
        assertThat(result.state().mealDraft()).isNull();
    }
    @Test void draftRoundTripsThroughSessionJsonAndAllWithMethods() {
        var draft = new MealDraft("d","番茄面 午餐",new MealDraftData("番茄面",List.of("午餐"),null,null,null,null,null,null,null),List.of(),List.of(),Map.of("name",List.of("番茄面")),MealDraftStatus.READY,null);
        SessionMapper mapper = mock(SessionMapper.class);
        final SessionRow[] row = new SessionRow[1];
        when(mapper.update(any())).thenAnswer(call -> { row[0]=call.getArgument(0); return 1; });
        when(mapper.findById("s",1L)).thenAnswer(call -> row[0]);
        var sessions = new SessionStateService(mapper,json);
        state = state.withMealDraft(draft).withPhase(SessionPhase.CLARIFY).withIntent(Intent.OTHER)
                .withSlots(SlotBundle.empty()).withSourceMode(SourceMode.PERSONAL).withLastRecommendations(List.of(3L))
                .appendLastRecommendations(List.of(4L)).withMealConstraintContext(state.mealConstraintContext())
                .withRecipeConversation(state.recipeConversation());
        sessions.save(state);
        var loaded = sessions.loadOrCreate("s",1L,SourceMode.PUBLIC);
        assertThat(loaded.mealDraft()).isEqualTo(draft);
        assertThat(loaded.lastRecommendations()).containsExactly(3L,4L);
        row[0].setSlots("{}");
        assertThat(sessions.loadOrCreate("s",1L,SourceMode.PUBLIC).mealDraft()).isNull();
    }
}
