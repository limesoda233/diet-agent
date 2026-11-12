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
    @Test void embeddedExplicitIntakeWithUnnamedMealCreatesMissingFieldDraft() {
        outputs.add("{\"ingredients\":[{\"value\":\"鸡肉\",\"evidence\":\"鸡肉\"}]}");
        var result = workflow.handle(1L,request("有鸡肉和花生，帮我录入一份餐食"),state).orElseThrow();
        assertThat(result.state().mealDraft().missingFields()).containsExactlyInAnyOrder("name","mealTime");
    }
    @Test void explicitNamedDishWithoutLibraryWordRoutesToDraft() {
        outputs.add("{\"name\":{\"value\":\"宫保鸡丁\",\"evidence\":\"宫保鸡丁\"}}");
        var result = workflow.handle(1L,request("帮我录入宫保鸡丁"),state).orElseThrow();
        assertThat(result.state().mealDraft().data().name()).isEqualTo("宫保鸡丁");
    }
    @Test void colloquialAddMealRequestRoutesToDraft() {
        outputs.add("{\"name\":{\"value\":\"番茄面\",\"evidence\":\"番茄面\"},\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"午餐\"}]}");
        var result = workflow.handle(1L,request("帮我添加番茄面，午餐吃"),state).orElseThrow();
        assertThat(result.state().mealDraft().data().name()).isEqualTo("番茄面");
    }
    @Test void ordinaryMessagesAndYesPassThrough() {
        for (String text : List.of("中午吃了宫保鸡丁","鸡肉能做什么菜","番茄面怎么做","是","好","如何录入系统日志","帮我录入系统日志","帮我录入鸡肉报告","如何录入番茄面？","不要录入番茄面"))
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
        assertThat(workflow.handle(1L,request("这些食材能做什么菜"),state)).isEmpty();
        outputs.add("{\"name\":{\"value\":\"宫保鸡丁\",\"evidence\":\"宫保鸡丁\"}}");
        state = workflow.handle(1L,request("宫保鸡丁"),state).orElseThrow().state();
        assertThat(state.mealDraft().data().name()).isEqualTo("宫保鸡丁");
    }
    @Test void bareNegativeIntakeDoesNotStartDraftButLaterAffirmativeClauseDoes() {
        assertThat(workflow.handle(1L,request("不录入番茄面"),state)).isEmpty();
        assertThat(workflow.handle(1L,request("不加入个人餐食库"),state)).isEmpty();
        outputs.add("{\"name\":{\"value\":\"宫保鸡丁\",\"evidence\":\"宫保鸡丁\"}}");
        assertThat(workflow.handle(1L,request("不录入番茄面，帮我录入宫保鸡丁"),state)
                .orElseThrow().state().mealDraft().data().name()).isEqualTo("宫保鸡丁");
    }
    @Test void fieldFirstSupplementUpdatesActiveDraft() {
        outputs.add("{\"name\":{\"value\":\"番茄面\",\"evidence\":\"番茄面\"},\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"中午\"}]}");
        state = workflow.handle(1L,request("帮我录入番茄面，中午吃"),state).orElseThrow().state();
        outputs.add("{\"ingredients\":[{\"value\":\"花生\",\"evidence\":\"花生\"}]}");
        state = workflow.handle(1L,request("食材补充花生"),state).orElseThrow().state();
        assertThat(state.mealDraft().data().name()).isEqualTo("番茄面");
        assertThat(state.mealDraft().data().mealTime()).containsExactly("午餐");
        assertThat(state.mealDraft().data().knowledge().ingredients()).contains("花生");
        outputs.add("{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}]}");
        assertThat(workflow.handle(1L,request("食材加上鸡蛋"),state)).isPresent();
        assertThat(workflow.handle(1L,request("食材怎么保存"),state)).isEmpty();
    }
    @Test void ingredientSupplementMergesButExplicitReplacementReplaces() {
        outputs.add("{\"name\":{\"value\":\"番茄面\",\"evidence\":\"番茄面\"},\"mealTime\":[{\"value\":\"午餐\",\"evidence\":\"中午\"}],\"ingredients\":[{\"value\":\"番茄\",\"evidence\":\"番茄\"}]}");
        state = workflow.handle(1L,request("帮我录入番茄面，中午吃，食材有番茄"),state).orElseThrow().state();
        outputs.add("{\"ingredients\":[{\"value\":\"鸡蛋\",\"evidence\":\"鸡蛋\"}]}");
        state = workflow.handle(1L,request("食材补充鸡蛋"),state).orElseThrow().state();
        assertThat(state.mealDraft().data().knowledge().ingredients()).containsExactly("番茄","鸡蛋");
        outputs.add("{\"ingredients\":[{\"value\":\"花生\",\"evidence\":\"花生\"}]}");
        state = workflow.handle(1L,request("食材改成花生"),state).orElseThrow().state();
        assertThat(state.mealDraft().data().knowledge().ingredients()).containsExactly("花生");
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
        var loaded = sessions.loadOrCreate("s",1L,SourceMode.PERSONAL);
        assertThat(loaded.mealDraft()).isEqualTo(draft);
        assertThat(loaded.lastRecommendations()).containsExactly(3L,4L);
        row[0].setSlots("{}");
        assertThat(sessions.loadOrCreate("s",1L,SourceMode.PERSONAL).mealDraft()).isNull();
    }
}
