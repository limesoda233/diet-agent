package com.diet.service.recipe;

import com.diet.agent.factory.AgentFactory;
import com.diet.enums.SourceMode;
import com.diet.mapper.AgentTraceMapper;
import com.diet.mapper.MealMapper;
import com.diet.model.*;
import com.diet.service.clarify.ClarifyAgentService;
import com.diet.service.intent.IntentAgentService;
import com.diet.service.intent.IntentReviseService;
import com.diet.service.meal.*;
import com.diet.service.meal.constraint.ConstraintParser;
import com.diet.service.meal.constraint.IngredientCatalog;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.orchestrator.DietOrchestratorService;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.answer.AgentScopeRecipeAnswerGenerator;
import com.diet.service.recipe.rag.answer.RecipeEvidenceAssembler;
import com.diet.service.recipe.rag.model.*;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.risk.RiskGuardService;
import com.diet.service.session.SessionService;
import com.diet.service.session.SessionStateService;
import com.diet.service.slot.SlotMergeService;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.video.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecipeVideoFinalFixTest {
    private static final CookingVideo VIDEO = new CookingVideo("宫保鸡丁", "教学视频",
            "https://example.invalid/video", VideoPlatform.BILIBILI, "厨房", 100, "controlled");

    @Test
    void bareRecipeFollowupUsesSingleDisplayedMealForBothBranches() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"}]}");
        fixture.chat("怎么做", Map.of());
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).isEqualTo("宫保鸡丁怎么做");
        assertThat(fixture.actualVideoText.get()).contains("宫保鸡丁");
        verifyNoInteractions(fixture.intent);
    }

    @Test
    void ambiguousFollowupAsksWithoutCallingRetrievalOrVideo() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"},{\"mealId\":2,\"name\":\"番茄炒蛋\"}]}");
        var response = fixture.chat("怎么做", Map.of());
        assertThat(response.responseType()).isEqualTo("CLARIFY");
        assertThat(response.clarifyQuestion()).contains("宫保鸡丁", "番茄炒蛋");
        verifyNoInteractions(fixture.retriever);
        assertThat(fixture.actualVideoText.get()).isNull();
    }

    @Test
    void ordinalFollowupResolvesDisplayOrder() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"},{\"mealId\":2,\"name\":\"番茄炒蛋\"}]}");
        fixture.chat("第二道怎么做", Map.of());
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).isEqualTo("番茄炒蛋怎么做");
    }

    @Test
    void noContextAsksForDishRatherThanSearchingBareHowTo() {
        var fixture = new Fixture("success");
        var response = fixture.chat("怎么做", Map.of());
        assertThat(response.responseType()).isEqualTo("CLARIFY");
        verifyNoInteractions(fixture.retriever);
    }

    @Test
    void recommendationThenHowToUsesPersistedDisplayedDish() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":99,\"name\":\"红烧肉\"}],\"activeDish\":\"红烧肉\"}");
        fixture.enableStatePersistence();
        var intent = new IntentResult(com.diet.enums.Intent.MEAL_RECOMMENDATION, SlotBundle.empty(), 1.0);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(intent);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(intent);
        when(fixture.clarify.decide(any(), any(), any())).thenReturn(ClarifyResult.ready());
        var meal = new MealItem(1L, SourceMode.PUBLIC, null, "宫保鸡丁", SlotBundle.empty(), 1.0);
        when(fixture.search.search(any())).thenReturn(List.of(meal));
        when(fixture.rank.rank(any())).thenReturn(List.of(meal));
        when(fixture.guard.check(any(), any(), any(), any())).thenReturn(RiskGuardResult.pass());
        when(fixture.recommend.recommendAndRespond(any(), any(), any(), any(), any())).thenReturn(
                new RecommendResponseAgentService.Result(new RecommendResult(List.of(
                        new RecommendedMealOption(1L, SourceMode.PUBLIC, "宫保鸡丁", "推荐", 1.0, SlotBundle.empty())), false),
                        new ResponseResult("推荐宫保鸡丁", List.of(MealResponse.from(meal)), "WAIT_USER")));
        assertThat(fixture.chat("推荐晚餐", Map.of()).displayBlocks()).hasSize(1);
        fixture.chat("怎么做", Map.of());
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).isEqualTo("宫保鸡丁怎么做");
        assertThat(fixture.actualVideoText.get()).contains("宫保鸡丁");
    }

    @Test
    void clarificationSelectionAndLaterFollowupKeepSelectedDish() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"},{\"mealId\":2,\"name\":\"番茄炒蛋\"}]}");
        fixture.enableStatePersistence();
        assertThat(fixture.chat("怎么做", Map.of()).responseType()).isEqualTo("CLARIFY");
        fixture.chat("番茄炒蛋", Map.of());
        fixture.chat("需要什么食材", Map.of());
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever, times(2)).retrieve(query.capture(), any());
        assertThat(query.getAllValues().get(0).text()).isEqualTo("番茄炒蛋怎么做");
        assertThat(query.getAllValues().get(1).text()).isEqualTo("番茄炒蛋需要什么食材");
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "三", " 3 ", "3。"})
    void numberSelectionAfterClarificationUsesFullDishNameForBothBranches(String reply) throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":38,\"name\":\"番茄豆腐煲\"},"
                + "{\"mealId\":40,\"name\":\"香菇青菜面\"},{\"mealId\":28,\"name\":\"低油版宫保鸡丁\"}]}");
        fixture.enableStatePersistence();
        // 模拟旧链路的模型兜底；修复后的序号选择不应依赖模型猜测意图。
        var fallback = new IntentResult(com.diet.enums.Intent.RECIPE_QUERY, SlotBundle.empty(), 0.85);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(fallback);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(fallback);

        assertThat(fixture.chat("怎么做", Map.of()).responseType()).isEqualTo("CLARIFY");
        verifyNoInteractions(fixture.retriever);
        assertThat(fixture.actualVideoText.get()).isNull();

        var response = fixture.chat(reply, Map.of());

        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).isEqualTo("低油版宫保鸡丁怎么做");
        assertThat(fixture.actualVideoText.get()).isEqualTo("低油版宫保鸡丁");
        assertThat(response.responseType()).isEqualTo("ANSWER");
        assertThat(response.speechText()).contains("[S1]");
        assertThat(response.videoBlocks()).hasSize(1);
        assertThat(fixture.states.loadOrCreate("s", 7L, SourceMode.PUBLIC)
                .recipeConversation().activeDish()).isEqualTo("低油版宫保鸡丁");
        verifyNoInteractions(fixture.intent);

        var trace = new ObjectMapper().readTree(fixture.rows.get(1).getTraceJson());
        assertThat(event(trace, "RECIPE_QUERY_NORMALIZED").path("outputPayload").asText())
                .contains("低油版宫保鸡丁怎么做", "低油版宫保鸡丁");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "4", "999999999999999999999999"})
    void numberSelectionOutOfRangeAsksAgainWithoutSearching(String reply) throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":38,\"name\":\"番茄豆腐煲\"},"
                + "{\"mealId\":40,\"name\":\"香菇青菜面\"},{\"mealId\":28,\"name\":\"低油版宫保鸡丁\"}],"
                + "\"pendingSelection\":true}");
        var fallback = new IntentResult(com.diet.enums.Intent.RECIPE_QUERY, SlotBundle.empty(), 0.85);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(fallback);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(fallback);

        var response = fixture.chat(reply, Map.of());

        assertThat(response.responseType()).isEqualTo("CLARIFY");
        assertThat(response.clarifyQuestion()).contains("低油版宫保鸡丁");
        verifyNoInteractions(fixture.retriever, fixture.intent);
        assertThat(fixture.actualVideoText.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void numberWithoutPendingDisplayedChoicesUsesNormalIntentRoute(boolean hasDisplayedMeals) throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation(hasDisplayedMeals
                ? "{\"displayedMeals\":[{\"mealId\":28,\"name\":\"低油版宫保鸡丁\"}],\"pendingSelection\":false}"
                : "{\"displayedMeals\":[],\"pendingSelection\":true}");
        var other = new IntentResult(com.diet.enums.Intent.OTHER, SlotBundle.empty(), 1.0);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(other);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(other);

        fixture.chat("3", Map.of());

        verify(fixture.intent).recognize(any(), anyLong(), eq("3"), any(), any());
        verifyNoInteractions(fixture.retriever);
        assertThat(fixture.actualVideoText.get()).isNull();
    }

    @Test
    void outOfRangeReferenceNeverGuessesDish() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"}]}");
        assertThat(fixture.chat("第二道怎么做", Map.of()).responseType()).isEqualTo("CLARIFY");
        verifyNoInteractions(fixture.retriever);
    }

    @Test
    void explicitIngredientQuestionReplacesOldSelection() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"}]}");
        fixture.enableStatePersistence();
        fixture.chat("番茄炒蛋需要什么食材", Map.of());
        fixture.chat("怎么做", Map.of());
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever, times(2)).retrieve(query.capture(), any());
        assertThat(query.getAllValues().get(1).text()).isEqualTo("番茄炒蛋怎么做");
    }

    @Test
    void structuredReferenceIsResolvedEvenWhenMessageAlreadyContainsDish() throws Exception {
        var fixture = new Fixture("success");
        fixture.restoreConversation("{\"displayedMeals\":[{\"mealId\":1,\"name\":\"宫保鸡丁\"},{\"mealId\":2,\"name\":\"番茄炒蛋\"}]}");
        fixture.chat("番茄炒蛋怎么做", Map.of("recipeQuery", Map.of("query", "第二道")));
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).isEqualTo("番茄炒蛋怎么做");
    }

    @Test
    void dishNameReplyAfterNoContextClarificationStartsRecipeFlow() {
        var fixture = new Fixture("success");
        fixture.enableStatePersistence();
        var other = new IntentResult(com.diet.enums.Intent.OTHER, SlotBundle.empty(), 1.0);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(other);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(other);
        fixture.chat("怎么做", Map.of());
        fixture.chat("宫保鸡丁", Map.of());
        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).isEqualTo("宫保鸡丁怎么做");
    }

    @ParameterizedTest
    @ValueSource(strings = {"做法", "咋做"})
    void repeatedBareQuestionContinuesClarificationInsteadOfBecomingDishName(String question) {
        var fixture = new Fixture("success");
        fixture.enableStatePersistence();
        fixture.chat("怎么做", Map.of());
        assertThat(fixture.chat(question, Map.of()).responseType()).isEqualTo("CLARIFY");
        verifyNoInteractions(fixture.retriever);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "disabled", "empty", "failed", "timeout"})
    void actualQueryServiceRetrievalFailureUsesRuntimeFailureMatrix(String videoState) throws Exception {
        var fixture = new Fixture(videoState);
        when(fixture.retriever.retrieve(any(), any())).thenReturn(
                Mono.error(new IllegalStateException("internal-sensitive-retriever-token")));

        var response = fixture.chat("宫保鸡丁怎么做", Map.of());

        boolean hasVideo = videoState.equals("success");
        assertThat(response.speechText()).contains(hasVideo ? "暂无可信文字菜谱" : "暂时不可用")
                .doesNotContain("internal-sensitive", "未找到可信菜谱或教学视频");
        assertThat(response.videoBlocks()).hasSize(hasVideo ? 1 : 0);
        var trace = fixture.persistedTrace();
        var failed = event(trace, "RECIPE_RAG_FAILED");
        assertThat(failed.path("outputPayload").asText()).contains("FAILED", "IllegalStateException");
        assertThat(trace.toString()).doesNotContain("internal-sensitive-retriever-token", "RECIPE_RAG_REFUSED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realClassifierKeepsIngredientAndVideoQuestionsOnRecipeRoute(boolean videosEnabled) {
        var fixture = new Fixture(videosEnabled ? "success" : "disabled");

        var response = fixture.chat("宫保鸡丁需要什么食材，有视频吗", Map.of());

        assertThat(response.speechText()).contains("鸡肉", "[S1]");
        assertThat(response.responseType()).isEqualTo("ANSWER");
        assertThat(response.videoBlocks()).hasSize(videosEnabled ? 1 : 0);
        verifyNoInteractions(fixture.intent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"冰箱里有鸡腿肉丁、花生米、辣椒、大葱，能做什么菜", "有鸡肉和花生，可以做哪些菜", "这些食材能做什么菜", "这些食材能做啥"})
    void ingredientSuggestionQuestionBypassesIntentAndKeepsOriginalQuestion(String question) {
        var fixture = new Fixture("disabled");
        var other = new IntentResult(com.diet.enums.Intent.OTHER, SlotBundle.empty(), 1.0);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(other);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(other);

        var response = fixture.chat(question, Map.of());

        assertThat(response.responseType()).isEqualTo("ANSWER");
        assertThat(response.speechText()).startsWith("根据本轮检索，可以优先考虑宫保鸡丁 [S1]")
                .contains("鸡肉", "[S1]");
        var captured = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(fixture.retriever).retrieve(captured.capture(), any());
        assertThat(captured.getValue().text()).isEqualTo(question);
        assertThat(captured.getValue().requiredIngredients()).isEmpty();
        verify(fixture.agent, times(1)).call(any(Msg.class));
        verifyNoInteractions(fixture.intent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"你能做什么", "Python可以做什么", "你能做啥", "这台电脑能做什么工作", "宫保鸡丁可以做什么口味", "推荐午餐",
            "买食材有什么推荐的超市", "食材采购推荐什么供应商"})
    void genericAbilityQuestionDoesNotBecomeIngredientRecipeSuggestion(String question) {
        var fixture = new Fixture("disabled");
        var other = new IntentResult(com.diet.enums.Intent.OTHER, SlotBundle.empty(), 1.0);
        when(fixture.intent.recognize(any(), anyLong(), any(), any(), any())).thenReturn(other);
        when(fixture.revise.revise(any(), any(), any())).thenReturn(other);

        var response = fixture.chat(question, Map.of());

        assertThat(response.speechText()).doesNotContain("[S1]", "优先考虑");
        verify(fixture.intent).recognize(any(), anyLong(), eq(question), any(), any());
        verifyNoInteractions(fixture.retriever);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ingredientAnswerTerminalGenerationIsPersistedInActualOrchestratorTrace(boolean validModelAnswer) throws Exception {
        var fixture = new Fixture("disabled");
        if (!validModelAnswer) {
            when(fixture.agent.call(any(Msg.class))).thenReturn(Mono.just(Msg.builder().role(MsgRole.ASSISTANT)
                    .textContent("invalid json").build()));
        }

        var response = fixture.chat("这些食材能做什么菜", Map.of());

        assertThat(response.speechText()).contains("宫保鸡丁", "[S1]");
        var payload = new ObjectMapper().readTree(event(fixture.persistedTrace(), "RECIPE_ANSWER_GENERATED")
                .path("outputPayload").asText());
        assertThat(payload.path("answerPurpose").asText()).isEqualTo("INGREDIENT_SUGGESTION");
        assertThat(payload.path("generation").asText()).isEqualTo(validModelAnswer ? "agent" : "deterministic_fallback");
        assertThat(payload.path("refused").asBoolean()).isFalse();
        assertThat(payload.path("citationCount").asInt()).isEqualTo(1);
        verify(fixture.agent, times(1)).call(any(Msg.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyRetrievalPersistsNotCalledGenerationWithoutAnswerModel(boolean parallelCoordinator) throws Exception {
        var fixture = new Fixture("disabled");
        when(fixture.retriever.retrieve(any(), any())).thenReturn(Mono.just(new RecipeRetrievalResult(List.of(), null)));
        if (!parallelCoordinator) fixture.orchestrator.setRecipeVideoCoordinator(null);

        var response = fixture.chat("这些食材能做啥", Map.of());

        assertThat(response.speechText()).doesNotContain("优先考虑", "[S1]");
        var payload = new ObjectMapper().readTree(event(fixture.persistedTrace(),
                parallelCoordinator ? "RECIPE_RAG_REFUSED" : "RECIPE_ANSWER_READY").path("outputPayload").asText());
        assertThat(payload.path("answerPurpose").asText()).isEqualTo("INGREDIENT_SUGGESTION");
        assertThat(payload.path("generation").asText()).isEqualTo("not_called");
        assertThat(payload.path("refused").asBoolean()).isTrue();
        assertThat(payload.path("citationCount").asInt()).isZero();
        verifyNoInteractions(fixture.agent);
    }

    @Test
    void structuredQueryOverridesMessageForBothActualBranchesAndTrace() throws Exception {
        var fixture = new Fixture("success");
        var actualRecipeText = new AtomicReference<String>();
        when(fixture.retriever.retrieve(any(), any())).thenAnswer(call -> {
            actualRecipeText.set(((RecipeQuery) call.getArgument(0)).text());
            return Mono.just(fixture.retrieval);
        });

        var response = fixture.chat("想学这个菜", Map.of("recipeQuery", Map.of("query", "宫保鸡丁怎么做")));

        assertThat(response.speechText()).contains("[S1]");
        assertThat(actualRecipeText).hasValue("宫保鸡丁怎么做");
        assertThat(fixture.actualVideoText).hasValue("宫保鸡丁");
        var normalized = new ObjectMapper().readTree(event(fixture.persistedTrace(), "RECIPE_QUERY_NORMALIZED")
                .path("outputPayload").asText());
        assertThat(normalized.path("recipeQuery").asText()).isEqualTo("宫保鸡丁怎么做");
        assertThat(normalized.path("videoQuery").asText()).isEqualTo("宫保鸡丁");
        assertThat(normalized.path("source").asText()).isEqualTo("structured_recipe_query");
    }

    @Test
    void mealNameIsResolvedBeforeBothBranchesAndOnlyLookedUpOnce() throws Exception {
        var fixture = new Fixture("success");
        var row = new MealItemRow();
        row.setName("番茄炒蛋");
        when(fixture.meals.findPersonalById(41L, 7L)).thenReturn(row);
        var actualRecipeText = new AtomicReference<String>();
        when(fixture.retriever.retrieve(any(), any())).thenAnswer(call -> {
            actualRecipeText.set(((RecipeQuery) call.getArgument(0)).text());
            return Mono.just(fixture.retrieval);
        });

        fixture.chat("这道菜怎么做", Map.of("recipeQuery", Map.of("mealId", 41)));

        assertThat(actualRecipeText).hasValue("番茄炒蛋 怎么做");
        assertThat(fixture.actualVideoText).hasValue("番茄炒蛋");
        verify(fixture.meals, times(1)).findPersonalById(41L, 7L);
        assertThat(event(fixture.persistedTrace(), "RECIPE_QUERY_NORMALIZED").path("outputPayload").asText())
                .contains("personal_meal", "番茄炒蛋");
    }

    @Test
    void invalidStructuredRecipeQueryIsClarifiedBeforeEitherBranchStarts() throws Exception {
        var fixture = new Fixture("success");
        clearInvocations(fixture.retriever);

        var response = fixture.chat("想学这个菜", Map.of("recipeQuery", Map.of("query", 123)));

        assertThat(response.responseType()).isEqualTo("CLARIFY");
        assertThat(response.missingSlots()).contains("recipeQuery");
        assertThat(fixture.actualVideoText).hasValue(null);
        verifyNoInteractions(fixture.retriever);
        assertThat(fixture.persistedTrace().toString()).doesNotContain("RECIPE_RAG_STARTED", "VIDEO_MCP_STARTED");
    }

    @Test
    void realAgentTraceIncludesCrossThreadCallTokensAndLatencyWithoutExtraFlush() throws Exception {
        var fixture = new Fixture("success");
        Thread caller = Thread.currentThread();

        var response = fixture.chat("宫保鸡丁怎么做", Map.of());

        assertThat(response.speechText()).contains("[S1]");
        assertThat(fixture.agentThread.get()).isNotSameAs(caller);
        assertThat(fixture.agentThread.get().getName()).startsWith("boundedElastic-");
        assertThat(fixture.rows).hasSize(1);
        var call = event(fixture.persistedTrace(), "AGENT_CALL");
        assertThat(call.path("agentName").asText()).isEqualTo("RecipeAnswerAgent");
        assertThat(call.path("modelName").asText()).isEqualTo("main");
        assertThat(call.path("inputTokens").asLong()).isEqualTo(11);
        assertThat(call.path("outputTokens").asLong()).isEqualTo(7);
        assertThat(call.path("totalTokens").asLong()).isEqualTo(18);
        assertThat(call.path("latencyMs").asLong()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void controlledAgentFailureIsRecordedInTheSameTraceAsGroundedFallback() throws Exception {
        var fixture = new Fixture("success");
        when(fixture.agent.call(any(Msg.class))).thenReturn(Mono.error(new IllegalStateException("controlled")));

        var response = fixture.chat("宫保鸡丁怎么做", Map.of());

        assertThat(response.speechText()).contains("[S1]");
        var call = event(fixture.persistedTrace(), "AGENT_CALL");
        assertThat(call.path("errorMessage").asText()).contains("IllegalStateException");
        assertThat(fixture.rows.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(fixture.rows).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"不要辣的菜谱", "只要清淡菜谱"})
    void recipeConstraintsWithoutVideoWordsStillReachRealRecipeOrchestration(String question) {
        var fixture = new Fixture("disabled");

        var response = fixture.chat(question, Map.of());

        assertThat(response.speechText()).contains("[S1]");
        assertThat(response.responseType()).isEqualTo("ANSWER");
        verifyNoInteractions(fixture.intent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"meal", "knowledge"})
    void mealPreparationDatabaseFailuresReturnStableUnavailableBeforeEitherBranch(String failureAt) throws Exception {
        var fixture = new Fixture("success");
        var sensitive = new IllegalStateException("internal-sensitive-database-token");
        if (failureAt.equals("meal")) {
            when(fixture.meals.findPersonalById(41L, 7L)).thenThrow(sensitive);
        } else {
            when(fixture.knowledge.findPersonal(41L, 7L)).thenThrow(sensitive);
        }

        var response = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                fixture.chat("这道菜怎么做", Map.of("recipeQuery", Map.of("mealId", 41))));

        assertThat(response.speechText()).contains("暂时不可用").doesNotContain("internal-sensitive");
        assertThat(response.videoBlocks()).isEmpty();
        verifyNoInteractions(fixture.retriever);
        assertThat(fixture.actualVideoText).hasValue(null);
        var trace = fixture.persistedTrace();
        assertThat(event(trace, "RECIPE_QUERY_PREPARATION_FAILED").path("outputPayload").asText())
                .contains("FAILED", "IllegalStateException").doesNotContain("internal-sensitive");
        assertThat(trace.toString()).doesNotContain("internal-sensitive-database-token", "RECIPE_RAG_STARTED", "VIDEO_MCP_STARTED");
    }

    private static JsonNode event(JsonNode trace, String name) {
        List<JsonNode> matches = new ArrayList<>();
        trace.path("events").forEach(value -> {
            if (name.equals(value.path("eventType").asText())) matches.add(value);
        });
        assertThat(matches).as("exactly one %s event", name).hasSize(1);
        return matches.get(0);
    }

    private static final class Fixture {
        final HybridRecipeRetriever retriever = mock(HybridRecipeRetriever.class);
        final MealMapper meals = mock(MealMapper.class);
        final MealKnowledgeRepository knowledge = mock(MealKnowledgeRepository.class);
        final ReActAgent agent = mock(ReActAgent.class);
        final IntentAgentService intent = mock(IntentAgentService.class);
        final SessionStateService states = mock(SessionStateService.class);
        final MealSearchService search = mock(MealSearchService.class);
        final MealRankService rank = mock(MealRankService.class);
        final ClarifyAgentService clarify = mock(ClarifyAgentService.class);
        final IntentReviseService revise = mock(IntentReviseService.class);
        final RecommendResponseAgentService recommend = mock(RecommendResponseAgentService.class);
        final RiskGuardService guard = mock(RiskGuardService.class);
        final List<RequestTraceRow> rows = new ArrayList<>();
        final AtomicReference<String> actualVideoText = new AtomicReference<>();
        final AtomicReference<Thread> agentThread = new AtomicReference<>();
        final RecipeRetrievalResult retrieval = new RecipeRetrievalResult(List.of(new RetrievedRecipe(
                new RecipeDocument("r1", "宫保鸡丁", List.of(), List.of("鸡肉"), List.of("炒制鸡肉"),
                        "", Map.of(), "path.md", "commit", "hash"), 1, List.of())), null);
        final DietOrchestratorService orchestrator;

        Fixture(String videoState) {
            var mapper = mock(AgentTraceMapper.class);
            doAnswer(call -> { rows.add(call.getArgument(0)); return 1; }).when(mapper).insert(any());
            var trace = new AgentTraceService(mapper, new ObjectMapper());
            when(agent.call(any(Msg.class))).thenAnswer(call -> {
                agentThread.set(Thread.currentThread());
                return Mono.delay(Duration.ofMillis(25)).map(ignored -> Msg.builder().role(MsgRole.ASSISTANT)
                        .textContent("{\"text\":\"食材：鸡肉 [S1]\",\"citationIds\":[\"S1\"],\"refused\":false}")
                        .metadata(Map.of("_chat_usage", new ChatUsage(11, 7, 0.025))).build());
            });
            var agents = mock(AgentFactory.class);
            when(agents.get("s")).thenReturn(new AgentFactory.AgentSet(null, null, null, agent));
            var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3),
                    agents, trace, new ObjectMapper());
            when(retriever.retrieve(any(), any())).thenReturn(Mono.just(retrieval));
            var recipes = new RecipeQueryService(new RecipeQueryRouter(), knowledge,
                    meals, query -> Mono.just(query), retriever,
                    (query, found) -> Mono.just(found), generator, new RecipeRagProperties());
            var properties = new VideoMcpProperties();
            properties.setEnabled(!videoState.equals("disabled"));
            properties.setTimeout(Duration.ofMillis(100));
            var videos = new CookingVideoSearchService((query, platform, limit) -> {
                actualVideoText.set(query);
                return switch (videoState) {
                    case "empty" -> Mono.just(List.of());
                    case "failed" -> Mono.error(new IllegalArgumentException("internal-video-token"));
                    case "timeout" -> Mono.never();
                    default -> Mono.just(List.of(VIDEO));
                };
            }, properties);
            when(states.loadOrCreate("s", 7L, SourceMode.PUBLIC))
                    .thenReturn(SessionState.fresh("s", 7L, SourceMode.PUBLIC));
            orchestrator = new DietOrchestratorService(mock(SessionService.class), states, intent,
                    revise, new SlotMergeService(), clarify,
                    search, rank, mock(MealPlanService.class),
                    recommend, mock(MealService.class), guard,
                    trace, new ConstraintParser(new IngredientCatalog()));
            orchestrator.setRecipeQueryService(recipes);
            orchestrator.setCookingVideoSearchService(videos);
            orchestrator.setRecipeVideoCoordinator(new RecipeVideoCoordinator(videos));
        }

        ChatResponse chat(String message, Map<String, Object> context) {
            return orchestrator.dietChat(7L, new ChatRequest("s", message, SourceMode.PUBLIC, context));
        }

        void restoreConversation(String context) throws Exception {
            var mapper = mock(com.diet.mapper.SessionMapper.class);
            var row = new SessionRow();
            row.setId("s"); row.setUserId(7L); row.setPhase("RECOMMEND");
            row.setLastRecommendations("[99,1,2]");
            row.setSlots("{\"_meta\":{\"sourceMode\":\"PUBLIC\",\"recipeConversation\":" + context + "}}");
            when(mapper.findById("s", 7L)).thenReturn(row);
            var restored = new SessionStateService(mapper, new ObjectMapper())
                    .loadOrCreate("s", 7L, SourceMode.PUBLIC);
            when(states.loadOrCreate("s", 7L, SourceMode.PUBLIC)).thenReturn(restored);
        }

        void enableStatePersistence() {
            var mapper = mock(com.diet.mapper.SessionMapper.class);
            var persisted = new AtomicReference<SessionRow>();
            var current = new SessionStateService(mapper, new ObjectMapper());
            var initial = states.loadOrCreate("s", 7L, SourceMode.PUBLIC);
            when(mapper.update(any())).thenAnswer(call -> { persisted.set(call.getArgument(0)); return 1; });
            when(mapper.findById("s", 7L)).thenAnswer(call -> persisted.get());
            doAnswer(call -> { current.save(call.getArgument(0)); return null; }).when(states).save(any());
            when(states.loadOrCreate("s", 7L, SourceMode.PUBLIC)).thenAnswer(call ->
                    persisted.get() == null ? initial : current.loadOrCreate("s", 7L, SourceMode.PUBLIC));
        }

        JsonNode persistedTrace() throws Exception {
            assertThat(rows).hasSize(1);
            return new ObjectMapper().readTree(rows.get(0).getTraceJson());
        }
    }
}
