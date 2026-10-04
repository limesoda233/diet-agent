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
        final ReActAgent agent = mock(ReActAgent.class);
        final IntentAgentService intent = mock(IntentAgentService.class);
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
            var recipes = new RecipeQueryService(new RecipeQueryRouter(), mock(MealKnowledgeRepository.class),
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
            var states = mock(SessionStateService.class);
            when(states.loadOrCreate("s", 7L, SourceMode.PUBLIC))
                    .thenReturn(SessionState.fresh("s", 7L, SourceMode.PUBLIC));
            orchestrator = new DietOrchestratorService(mock(SessionService.class), states, intent,
                    mock(IntentReviseService.class), new SlotMergeService(), mock(ClarifyAgentService.class),
                    mock(MealSearchService.class), mock(MealRankService.class), mock(MealPlanService.class),
                    mock(RecommendResponseAgentService.class), mock(MealService.class), mock(RiskGuardService.class),
                    trace, new ConstraintParser(new IngredientCatalog()));
            orchestrator.setRecipeQueryService(recipes);
            orchestrator.setCookingVideoSearchService(videos);
            orchestrator.setRecipeVideoCoordinator(new RecipeVideoCoordinator(videos));
        }

        ChatResponse chat(String message, Map<String, Object> context) {
            return orchestrator.dietChat(7L, new ChatRequest("s", message, SourceMode.PUBLIC, context));
        }

        JsonNode persistedTrace() throws Exception {
            assertThat(rows).hasSize(1);
            return new ObjectMapper().readTree(rows.get(0).getTraceJson());
        }
    }
}
