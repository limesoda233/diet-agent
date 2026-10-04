package com.diet.service.recipe;

import com.diet.enums.Intent;
import com.diet.enums.SourceMode;
import com.diet.model.ChatRequest;
import com.diet.model.ChatResponse;
import com.diet.service.video.CookingVideo;
import com.diet.service.video.CookingVideoSearchService;
import com.diet.service.video.VideoMcpProperties;
import com.diet.service.video.VideoPlatform;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import java.util.stream.Stream;
import static com.diet.service.recipe.RecipeVideoCoordinator.BranchStatus.*;
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

    private static RecipeAnswer grounded() {
        return new RecipeAnswer("宫保鸡丁做法 [S1]",
                List.of(new RecipeCitation("S1", "r1", "宫保鸡丁", "path.md", "commit", "hash")),
                false, Map.of("internal", "secret-token"));
    }

    private static CookingVideo video() {
        return new CookingVideo("宫保鸡丁", "视频标题不是菜谱证据", "https://www.bilibili.com/video/BV1abc",
                VideoPlatform.BILIBILI, "厨房", 120, "mcp:bilibili");
    }

    static Stream<Arguments> responseMatrix() {
        var refusal = RecipeAnswer.refused("公共语料暂无可信菜谱。", Map.of());
        return Stream.of(
                Arguments.of(SUCCESS, grounded(), SUCCESS, "宫保鸡丁做法 [S1]", 1, "ANSWER"),
                Arguments.of(SUCCESS, grounded(), TIMEOUT, "宫保鸡丁做法 [S1]", 0, "ANSWER"),
                Arguments.of(SUCCESS, grounded(), EMPTY, "宫保鸡丁做法 [S1]", 0, "ANSWER"),
                Arguments.of(SUCCESS, grounded(), FAILED, "宫保鸡丁做法 [S1]", 0, "ANSWER"),
                Arguments.of(SUCCESS, grounded(), DISABLED, "宫保鸡丁做法 [S1]", 0, "ANSWER"),
                Arguments.of(REFUSED, refusal, SUCCESS, "暂无可信菜谱", 1, "VIDEOS"),
                Arguments.of(REFUSED, refusal, EMPTY, "未找到可信菜谱或教学视频", 0, "ANSWER"),
                Arguments.of(FAILED, null, SUCCESS, "暂无可信文字菜谱", 1, "VIDEOS"),
                Arguments.of(FAILED, null, FAILED, "暂时不可用", 0, "ANSWER"));
    }

    @ParameterizedTest
    @MethodSource("responseMatrix")
    void composesAvailableBranchesAndPersistsRecipeIntent(
            RecipeVideoCoordinator.BranchStatus recipeStatus, RecipeAnswer answer,
            RecipeVideoCoordinator.BranchStatus videoStatus, String text, int cards, String type) {
        var fixture = new Fixture();
        var coordinator = mock(RecipeVideoCoordinator.class);
        when(coordinator.recipeVideoRequest(anyString())).thenAnswer(call ->
                new CookingVideoSearchService((query, platform, limit) -> Mono.just(List.of()),
                        new VideoMcpProperties()).forRecipe(call.getArgument(0)));
        fixture.orchestrator.setRecipeVideoCoordinator(coordinator);
        when(fixture.recipes.matches("宫保鸡丁怎么做", Map.of())).thenReturn(true);
        var result = new RecipeVideoCoordinator.Result(
                new RecipeVideoCoordinator.BranchOutcome<>(recipeStatus, answer, "secret-token\nurl", 20),
                new RecipeVideoCoordinator.BranchOutcome<>(videoStatus, cards > 0 ? List.of(video()) : List.of(),
                        "secret-token\nurl", 15));
        when(coordinator.executePrepared(any(), any())).thenReturn(Mono.just(result));

        var response = fixture.chat("宫保鸡丁怎么做");

        fixture.assertRecipeResponse(response, text, cards, type);
        assertThat(response.speechText()).doesNotContain("secret-token", "视频标题", "https://");
        verify(fixture.trace).recordEvent(eq("RECIPE_QUERY_NORMALIZED"), anyString(), any(), any());
        verify(fixture.trace).recordEvent(eq("RECIPE_RAG_STARTED"), anyString(), any(), any());
        verify(fixture.trace).recordEvent(eq("VIDEO_MCP_STARTED"), anyString(), any(), any());
        String recipeEvent = recipeStatus == SUCCESS ? "RECIPE_RAG_COMPLETED"
                : recipeStatus == REFUSED ? "RECIPE_RAG_REFUSED" : "RECIPE_RAG_FAILED";
        String videoEvent = switch (videoStatus) {
            case SUCCESS -> "VIDEO_MCP_COMPLETED";
            case EMPTY -> "VIDEO_MCP_EMPTY";
            case TIMEOUT -> "VIDEO_MCP_TIMEOUT";
            default -> "VIDEO_MCP_FAILED";
        };
        var recipePayload = ArgumentCaptor.forClass(Object.class);
        var videoPayload = ArgumentCaptor.forClass(Object.class);
        verify(fixture.trace).recordEvent(eq(recipeEvent), anyString(), isNull(), recipePayload.capture());
        verify(fixture.trace).recordEvent(eq(videoEvent), anyString(), isNull(), videoPayload.capture());
        assertThat(recipePayload.getValue().toString()).doesNotContain("secret-token", "internal", "path.md");
        assertThat(videoPayload.getValue().toString()).doesNotContain("secret-token", "https://");
        var recipeTerminalEvents = java.util.Set.of("RECIPE_RAG_COMPLETED", "RECIPE_RAG_REFUSED", "RECIPE_RAG_FAILED");
        var videoTerminalEvents = java.util.Set.of("VIDEO_MCP_COMPLETED", "VIDEO_MCP_EMPTY", "VIDEO_MCP_TIMEOUT", "VIDEO_MCP_FAILED");
        var events = mockingDetails(fixture.trace).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("recordEvent"))
                .map(invocation -> (String) invocation.getArgument(0)).toList();
        assertThat(events.stream().filter(recipeTerminalEvents::contains).count()).isEqualTo(1);
        assertThat(events.stream().filter(videoTerminalEvents::contains).count()).isEqualTo(1);
        verify(fixture.trace).recordEvent(eq("RECIPE_RESPONSE_COMPOSED"), anyString(), isNull(), any());
        verify(fixture.trace, never()).recordError(anyString(), anyString(), any(), any());
        verifyNoInteractions(fixture.videos);
    }

    @Test
    void mixedRecipeAndVideoRequestUsesCoordinatorInsteadOfPureVideoRoute() {
        var fixture = new Fixture();
        fixture.orchestrator.setCookingVideoSearchService(fixture.videos);
        var coordinator = mock(RecipeVideoCoordinator.class);
        when(coordinator.recipeVideoRequest(anyString())).thenAnswer(call ->
                new CookingVideoSearchService((query, platform, limit) -> Mono.just(List.of()),
                        new VideoMcpProperties()).forRecipe(call.getArgument(0)));
        fixture.orchestrator.setRecipeVideoCoordinator(coordinator);
        String question = "宫保鸡丁怎么做，有视频吗";
        when(fixture.videos.matches(question, Map.of())).thenReturn(true);
        when(fixture.videos.isVideoOnly(question, Map.of())).thenReturn(false);
        when(fixture.recipes.matches(question, Map.of())).thenReturn(true);
        when(coordinator.executePrepared(any(), any())).thenReturn(Mono.just(new RecipeVideoCoordinator.Result(
                new RecipeVideoCoordinator.BranchOutcome<>(SUCCESS, grounded(), "", 20),
                new RecipeVideoCoordinator.BranchOutcome<>(SUCCESS, List.of(video()), "", 15))));

        fixture.assertRecipeResponse(fixture.chat(question), "[S1]", 1, "ANSWER");
        verify(fixture.videos, never()).search(any(CookingVideoSearchService.VideoSearchRequest.class));
        verify(coordinator).executePrepared(any(), argThat(query -> query.query().equals("宫保鸡丁")));
    }

    @Test
    void explicitVideoOnlyRequestSkipsRecipeAndKeepsRecipeIntent() {
        var fixture = new Fixture();
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        var videos = new CookingVideoSearchService((query, platform, limit) -> Mono.just(List.of(video())), properties);
        fixture.orchestrator.setCookingVideoSearchService(videos);
        fixture.orchestrator.setRecipeVideoCoordinator(mock(RecipeVideoCoordinator.class));

        fixture.assertRecipeResponse(fixture.chat("只要宫保鸡丁视频"), "找到", 1, "VIDEOS");
        verifyNoInteractions(fixture.recipes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void videoProviderArgumentFailureNeverLeaksIntoResponseOrTrace(boolean synchronous) {
        var fixture = new Fixture();
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        String sensitive = "internal-sensitive-provider-token";
        var videos = new CookingVideoSearchService((query, platform, limit) -> {
            var failure = new IllegalArgumentException(sensitive);
            if (synchronous) throw failure;
            return Mono.error(failure);
        }, properties);
        fixture.orchestrator.setCookingVideoSearchService(videos);

        var response = fixture.chat("只要宫保鸡丁视频");

        assertThat(response.speechText()).doesNotContain(sensitive);
        fixture.assertRecipeResponse(response, "视频搜索暂时不可用", 0, "ANSWER");
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(fixture.trace).recordEvent(eq("COOKING_VIDEO_SEARCH_FAILED"), eq("VIDEO_MCP"),
                isNull(), payload.capture());
        assertThat(payload.getValue()).isEqualTo(Map.of("status", FAILED, "errorType", "IllegalArgumentException"));
        assertThat(mockingDetails(fixture.trace).getInvocations().toString()).doesNotContain(sensitive);
        verify(fixture.trace, never()).recordError(anyString(), anyString(), any(), any());
        verifyNoInteractions(fixture.recipes);
    }

    @Test
    void invalidVideoInputGetsStableClarificationBeforeProviderIsCalled() {
        var fixture = new Fixture();
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        var videos = new CookingVideoSearchService((query, platform, limit) -> {
            throw new AssertionError("invalid route must not call provider");
        }, properties);
        fixture.orchestrator.setCookingVideoSearchService(videos);

        var response = fixture.orchestrator.dietChat(7L, new ChatRequest("s", "找视频", SourceMode.PUBLIC,
                Map.of("videoSearch", Map.of("query", ""))));

        assertThat(response.responseType()).isEqualTo("CLARIFY");
        assertThat(response.speechText()).contains("视频搜索参数无效");
        assertThat(response.missingSlots()).containsExactly("videoSearch");
        verifyNoInteractions(fixture.recipes, fixture.intent, fixture.meals, fixture.mealSearch);
    }

    @Test
    void disabledVideoSearchPreservesGroundedRecipeAnswer() {
        var fixture = new Fixture();
        var videos = new CookingVideoSearchService((query, platform, limit) -> {
            throw new AssertionError("disabled provider must not be called");
        }, new VideoMcpProperties());
        fixture.orchestrator.setCookingVideoSearchService(videos);
        fixture.orchestrator.setRecipeVideoCoordinator(new RecipeVideoCoordinator(videos));
        when(fixture.recipes.matches("宫保鸡丁怎么做", Map.of())).thenReturn(true);
        when(fixture.recipes.answer("s", 7L, "宫保鸡丁怎么做", Map.of())).thenReturn(grounded());

        fixture.assertRecipeResponse(fixture.chat("宫保鸡丁怎么做"), "[S1]", 0, "ANSWER");
    }

    private static class Fixture {
        final SessionService sessions = mock(SessionService.class);
        final SessionStateService states = mock(SessionStateService.class);
        final IntentAgentService intent = mock(IntentAgentService.class);
        final MealService meals = mock(MealService.class);
        final MealSearchService mealSearch = mock(MealSearchService.class);
        final AgentTraceService trace = mock(AgentTraceService.class);
        final RecipeQueryService recipes = mock(RecipeQueryService.class);
        final CookingVideoSearchService videos = mock(CookingVideoSearchService.class);
        final DietOrchestratorService orchestrator;

        Fixture() {
            when(trace.propagate(any())).thenAnswer(call -> call.getArgument(0));
            when(recipes.prepareQuery(anyLong(), anyString(), any())).thenAnswer(call -> {
                var routed = new RecipeQueryRouter().route(call.getArgument(1), call.getArgument(2)).orElseThrow();
                return new RecipeQueryService.PreparedRecipeQuery(routed.query(), null, null, false, "recipe_rule");
            });
            when(recipes.answer(anyString(), any(RecipeQueryService.PreparedRecipeQuery.class))).thenAnswer(call -> {
                RecipeQueryService.PreparedRecipeQuery prepared = call.getArgument(1);
                return recipes.answer(call.getArgument(0), 7L, prepared.query().text(), Map.of());
            });
            when(states.loadOrCreate("s", 7L, SourceMode.PUBLIC))
                    .thenReturn(SessionState.fresh("s", 7L, SourceMode.PUBLIC));
            orchestrator = new DietOrchestratorService(sessions, states, intent, mock(IntentReviseService.class),
                    new SlotMergeService(), mock(ClarifyAgentService.class), mealSearch,
                    mock(MealRankService.class), mock(MealPlanService.class), mock(RecommendResponseAgentService.class),
                    meals, mock(RiskGuardService.class), trace, new ConstraintParser(new IngredientCatalog()));
            orchestrator.setRecipeQueryService(recipes);
        }

        ChatResponse chat(String text) {
            return orchestrator.dietChat(7L, new ChatRequest("s", text, SourceMode.PUBLIC, Map.of()));
        }

        void assertRecipeResponse(ChatResponse response, String text, int cards, String type) {
            assertThat(response.speechText()).contains(text);
            assertThat(response.videoBlocks()).hasSize(cards);
            assertThat(response.displayBlocks()).isEmpty();
            assertThat(response.responseType()).isEqualTo(type);
            verify(states).save(argThat(saved -> saved.currentIntent() == Intent.RECIPE_QUERY));
            verify(sessions).appendMessage(eq("s"), eq("assistant"), eq(response.speechText()),
                    eq(Intent.RECIPE_QUERY.name()), eq(response.traceId()));
            verifyNoInteractions(intent, meals, mealSearch);
        }
    }
}
