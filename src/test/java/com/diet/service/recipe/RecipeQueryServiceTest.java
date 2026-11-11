package com.diet.service.recipe;

import com.diet.mapper.MealMapper;
import com.diet.model.MealItemRow;
import com.diet.service.meal.knowledge.MealKnowledge;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.answer.RecipeAnswerGenerator;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.recipe.rag.retrieval.RecipeRerankResult;
import com.diet.service.trace.AgentTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecipeQueryServiceTest {
    private MealKnowledgeRepository knowledge;
    private MealMapper meals;
    private HybridRecipeRetriever retriever;
    private RecipeAnswerGenerator generator;
    private RecipeQueryService service;

    @BeforeEach
    void setUp() {
        knowledge = mock(MealKnowledgeRepository.class);
        meals = mock(MealMapper.class);
        retriever = mock(HybridRecipeRetriever.class);
        generator = mock(RecipeAnswerGenerator.class);
        RecipeQueryRewriter rewriter = query -> Mono.just(query);
        RecipeReranker reranker = (query, recipes) -> Mono.just(recipes);
        service = new RecipeQueryService(new RecipeQueryRouter(), knowledge, meals, rewriter,
                retriever, reranker, generator, new RecipeRagProperties());
    }

    @Test
    void answersDirectRecipeQuestionWithoutMealRecommendation() {
        var document = new RecipeDocument("r1", "宫保鸡丁", List.of(), List.of("鸡肉"),
                List.of("炒制"), "", Map.of(), "path.md", "commit", "hash");
        var retrieval = new RecipeRetrievalResult(List.of(new RetrievedRecipe(document, 1, List.of())), null);
        when(retriever.retrieve(any(), any())).thenReturn(Mono.just(retrieval));
        var expected = new RecipeAnswer("宫保鸡丁做法 [S1]", List.of(
                new RecipeCitation("S1", "r1", "宫保鸡丁", "path.md", "commit", "hash")), false, Map.of());
        when(generator.generate(eq("s"), any(), any())).thenReturn(expected);

        assertThat(service.answer("s", 7L, "宫保鸡丁怎么做", Map.of())).isEqualTo(expected);
        verify(retriever).retrieve(any(RecipeQuery.class), any());
    }

    @Test
    void personalPreparationWinsAndDoesNotCallPublicRag() {
        when(knowledge.findPersonal(41L, 7L)).thenReturn(Optional.of(new MealKnowledge(
                List.of("番茄", "鸡蛋"), "用户常做", "先炒蛋再炒番茄", "USER_PROVIDED", "用户录入")));

        var answer = service.answer("s", 7L, "这道菜怎么做",
                Map.of("recipeQuery", Map.of("mealId", 41)));

        assertThat(answer.text()).contains("用户录入", "先炒蛋再炒番茄", "[P1]");
        assertThat(answer.citations()).singleElement().extracting(RecipeCitation::id).isEqualTo("P1");
        verifyNoInteractions(retriever, generator, meals);
    }

    @Test
    void missingPersonalPreparationFallsBackToPublicRecipeByMealName() {
        when(knowledge.findPersonal(41L, 7L)).thenReturn(Optional.empty());
        var row = new MealItemRow();
        row.setId(41L); row.setName("番茄炒蛋"); row.setSourceType("PERSONAL"); row.setOwnerUserId(7L);
        when(meals.findPersonalById(41L, 7L)).thenReturn(row);
        when(retriever.retrieve(any(), any())).thenReturn(Mono.just(emptyRetrieval()));
        when(generator.generate(eq("s"), any(), any())).thenReturn(RecipeAnswer.refused("没有足够证据", Map.of()));

        service.answer("s", 7L, "这道菜怎么做", Map.of("recipeQuery", Map.of("mealId", 41)));

        var query = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(retriever).retrieve(query.capture(), any());
        assertThat(query.getValue().text()).contains("番茄炒蛋");
    }

    @Test
    void refusesPersonalPreparationThatConflictsWithExplicitIngredients() {
        when(knowledge.findPersonal(41L, 7L)).thenReturn(Optional.of(new MealKnowledge(
                List.of("番茄", "鸡蛋"), "用户常做", "先炒蛋再炒番茄", "USER_PROVIDED", "用户录入")));

        var answer = service.answer("s", 7L, "这道菜怎么做", Map.of("recipeQuery", Map.of(
                "mealId", 41, "excludedIngredients", List.of("鸡蛋"))));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.diagnostics()).containsEntry("route", "personal_knowledge_constraint_conflict");
        verifyNoInteractions(retriever, generator, meals);
    }

    @Test
    void refusesOutOfCorpusQuestionAndRejectsUnknownStructuredFields() {
        when(retriever.retrieve(any(), any())).thenReturn(Mono.just(emptyRetrieval()));
        assertThat(service.answer("s", 7L, "量子计算机怎么做", Map.of()).refused()).isTrue();
        verifyNoInteractions(generator);

        assertThatThrownBy(() -> service.answer("s", 7L, "做法", Map.of(
                "recipeQuery", Map.of("query", "番茄炒蛋", "temperature", 0.9))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("temperature");
    }

    @Test
    void usesRewrittenQueryForRetrievalButOriginalQuestionForAnswerGeneration() {
        RecipeQueryRewriter rewriter = original -> Mono.just(new RecipeQuery(
                "酸辣土豆丝 做法", original.requiredIngredients(), original.excludedIngredients(),
                original.requiredTools(), original.maxMinutes()));
        RecipeReranker reranker = (query, recipes) -> Mono.just(recipes);
        service = new RecipeQueryService(new RecipeQueryRouter(), knowledge, meals, rewriter,
                retriever, reranker, generator, new RecipeRagProperties());
        var document = new RecipeDocument("r1", "酸辣土豆丝", List.of(), List.of("土豆"),
                List.of("炒制"), "", Map.of(), "path.md", "commit", "hash");
        var retrieval = new RecipeRetrievalResult(List.of(new RetrievedRecipe(document, 1, List.of())), null);
        when(retriever.retrieve(any(), any())).thenReturn(Mono.just(retrieval));
        when(generator.generate(eq("s"), any(), any())).thenReturn(
                new RecipeAnswer("答案", List.of(), false, Map.of()));

        service.answer("s", 7L, "马铃薯丝怎么炒得酸辣", Map.of(
                "recipeQuery", Map.of("query", "马铃薯丝怎么炒得酸辣")));

        var retrievalQuery = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(retriever).retrieve(retrievalQuery.capture(), any());
        assertThat(retrievalQuery.getValue().text()).isEqualTo("酸辣土豆丝 做法");
        var answerQuery = org.mockito.ArgumentCaptor.forClass(RecipeQuery.class);
        verify(generator).generate(eq("s"), answerQuery.capture(), any());
        assertThat(answerQuery.getValue().text()).isEqualTo("马铃薯丝怎么炒得酸辣");
    }

    @Test
    void filteredCandidatesStopBeforeGenerationAndRecordAdmissionOutcome() {
        var trace = mock(AgentTraceService.class);
        service = serviceWithResult(RecipeRerankResult.Status.FILTERED, trace);
        var answer = service.answer("s", 7L, "新疆烤包子怎么做", Map.of());
        assertThat(answer.refused()).isTrue();
        assertThat(answer.diagnostics()).containsEntry("route", "no_relevant_evidence")
                .containsEntry("generation", "not_called");
        verifyNoInteractions(generator);
        verify(trace).recordEvent(eq("RECIPE_RELEVANCE_CHECKED"), eq("RECIPE_RAG"), isNull(), any(), anyLong());
    }

    @Test
    void unavailableScorerDoesNotBecomeSuccessfulEmptyRetrievalOrCallGenerator() {
        service = serviceWithResult(RecipeRerankResult.Status.UNAVAILABLE, null);
        var answer = service.answer("s", 7L, "新疆烤包子怎么做", Map.of());
        assertThat(answer.refused()).isTrue();
        assertThat(answer.diagnostics()).containsEntry("route", "relevance_failed")
                .containsEntry("generation", "not_called");
        assertThat(answer.text()).contains("暂时不可用");
        verifyNoInteractions(generator);
    }

    @Test
    void rerankingUsesResolvedOriginalQuestionAndPreservesScoringDiagnosticsInAnswer() {
        var document = new RecipeDocument("r1", "酸辣土豆丝", List.of(), List.of("土豆"),
                List.of("炒制"), "", Map.of(), "path.md", "commit", "hash");
        var recipes = List.of(new RetrievedRecipe(document, 0.03, List.of()));
        when(retriever.retrieve(any(), any())).thenReturn(Mono.just(new RecipeRetrievalResult(recipes, null)));
        RecipeReranker reranker = mock(RecipeReranker.class);
        when(reranker.rerankDetailed(any(), any())).thenReturn(Mono.just(new RecipeRerankResult(
                RecipeRerankResult.Status.ACCEPTED, recipes, List.of(new RecipeRerankResult.CandidateScore("r1", 0.03, 0.9)),
                "test-reranker", 0.5, 1, 10, "", "query-hash", List.of("doc-hash"))));
        service = new RecipeQueryService(new RecipeQueryRouter(), knowledge, meals,
                original -> Mono.just(new RecipeQuery("土豆丝", List.of(), List.of(), List.of(), null)),
                retriever, reranker, generator, new RecipeRagProperties());
        when(generator.generate(any(), any(), any())).thenReturn(new RecipeAnswer("答案 [S1]", List.of(), false,
                Map.of("generation", "agent")));
        var answer = service.answer("s", 7L, "马铃薯丝怎么炒得酸辣", Map.of(
                "recipeQuery", Map.of("query", "马铃薯丝怎么炒得酸辣")));
        verify(reranker).rerankDetailed(argThat(query -> query.text().equals("马铃薯丝怎么炒得酸辣")), eq(recipes));
        assertThat(answer.diagnostics()).containsKey("relevance");
        assertThat(answer.diagnostics().get("relevance").toString()).contains("0.03", "0.9", "ACCEPTED");
    }

    @Test
    void enabledScoringGetsWiderParentPoolWhileDisabledPathKeepsOriginalBudget() {
        var config = new RecipeRagProperties();
        var factory = new RecipeQueryConfiguration();
        var sparse = mock(com.diet.service.recipe.rag.index.RecipeSparseIndex.class);
        var embedding = mock(com.diet.service.recipe.rag.index.RecipeEmbeddingModel.class);
        var vector = mock(com.diet.service.recipe.rag.index.RecipeVectorIndex.class);
        var parents = new com.diet.service.recipe.rag.retrieval.RecipeParentResolver(List.of());
        var baseline = factory.hybridRecipeRetriever(sparse, embedding, vector, parents, config);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(baseline, "finalTopK")).isEqualTo(5);
        config.getRerank().setEnabled(true);
        config.getRerank().setCandidateTopK(20);
        var enabled = factory.hybridRecipeRetriever(sparse, embedding, vector, parents, config);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(enabled, "finalTopK")).isEqualTo(20);
    }

    private RecipeQueryService serviceWithResult(RecipeRerankResult.Status status, AgentTraceService trace) {
        var document = new RecipeDocument("r1", "普通包子", List.of(), List.of("面粉"),
                List.of("蒸制"), "", Map.of(), "path.md", "commit", "hash");
        when(retriever.retrieve(any(), any())).thenReturn(Mono.just(new RecipeRetrievalResult(
                List.of(new RetrievedRecipe(document, 0.03, List.of())), null)));
        RecipeReranker reranker = mock(RecipeReranker.class);
        when(reranker.rerankDetailed(any(), any())).thenReturn(Mono.just(new RecipeRerankResult(
                status, List.of(), List.of(), "test-reranker", 0.5, 1, 10,
                status == RecipeRerankResult.Status.UNAVAILABLE ? "http_500" : "", "hash", List.of("hash"))));
        return new RecipeQueryService(new RecipeQueryRouter(), knowledge, meals, query -> Mono.just(query),
                retriever, reranker, generator, new RecipeRagProperties(), trace);
    }

    private RecipeRetrievalResult emptyRetrieval() {
        return new RecipeRetrievalResult(List.of(), null);
    }
}
