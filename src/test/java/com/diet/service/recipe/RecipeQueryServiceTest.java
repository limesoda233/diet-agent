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

    private RecipeRetrievalResult emptyRetrieval() {
        return new RecipeRetrievalResult(List.of(), null);
    }
}
