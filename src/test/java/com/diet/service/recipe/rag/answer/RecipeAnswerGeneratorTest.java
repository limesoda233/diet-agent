package com.diet.service.recipe.rag.answer;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;
import com.diet.agent.factory.AgentFactory;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecipeAnswerGeneratorTest {

    @ParameterizedTest
    @ValueSource(strings = {"agent", "invalid_json", "invalid_citation", "model_error", "refused", "no_evidence", "named_recipe"})
    void recordsOneSanitizedTerminalEventForEveryAnswerGenerationOutcome(String outcome) throws Exception {
        var mapper = mock(AgentTraceMapper.class);
        var persisted = new AtomicReference<RequestTraceRow>();
        doAnswer(call -> { persisted.set(call.getArgument(0)); return 1; }).when(mapper).insert(any());
        var trace = spy(new AgentTraceService(mapper, new ObjectMapper()));
        var agents = mock(AgentFactory.class);
        var agent = mock(ReActAgent.class);
        when(agents.get("s")).thenReturn(new AgentFactory.AgentSet(null, null, null, agent));
        if (outcome.equals("model_error")) {
            when(agent.call(any(Msg.class))).thenReturn(Mono.error(new IllegalStateException("sensitive-model-error")));
        } else {
            String raw = switch (outcome) {
                case "invalid_json" -> "invalid json";
                case "invalid_citation" -> "{\"text\":\"invalid [S9]\",\"citationIds\":[\"S9\"]}";
                case "refused" -> "{\"refused\":true}";
                default -> "{\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"]}";
            };
            when(agent.call(any(Msg.class))).thenReturn(Mono.just(Msg.builder().role(MsgRole.ASSISTANT).textContent(raw).build()));
        }
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), agents, trace, new ObjectMapper());
        RecipeAnswer answer;
        try (var scope = trace.openTrace("t", "s", 7L)) {
            answer = generator.generate("s", outcome.equals("named_recipe") ? query() : ingredientQuery(),
                    outcome.equals("no_evidence") ? retrieval() : retrieval(kungPao("r1")));
        }

        boolean refused = outcome.equals("refused") || outcome.equals("no_evidence");
        String generation = switch (outcome) {
            case "no_evidence" -> "not_called";
            case "invalid_json", "invalid_citation", "model_error" -> "deterministic_fallback";
            default -> "agent";
        };
        String purpose = outcome.equals("named_recipe") ? "RECIPE" : "INGREDIENT_SUGGESTION";
        assertThat(answer.refused()).isEqualTo(refused);
        var captured = ArgumentCaptor.forClass(Object.class);
        verify(trace, times(1)).recordEvent(eq("RECIPE_ANSWER_GENERATED"), eq("RECIPE"), isNull(), captured.capture());
        assertThat(captured.getValue()).isEqualTo(Map.of("answerPurpose", purpose, "generation", generation,
                "refused", refused, "citationCount", refused ? 0 : 1));
        var recorded = new ObjectMapper().readTree(persisted.get().getTraceJson());
        var terminalEvents = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        recorded.path("events").forEach(event -> {
            if (event.path("eventType").asText().equals("RECIPE_ANSWER_GENERATED")) terminalEvents.add(event);
        });
        assertThat(terminalEvents).singleElement().satisfies(event -> {
            assertThat(event.path("outputPayload").asText()).contains(purpose, generation).doesNotContain("sensitive-model-error");
        });
        verify(agent, times(outcome.equals("no_evidence") ? 0 : 1)).call(any(Msg.class));
    }

    @Test
    void ingredientSuggestionAddsCitedPrefaceForRecipeOnlyModelOutputInOneCall() {
        var calls = new AtomicInteger();
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), prompt -> {
            calls.incrementAndGet();
            return "{\"text\":\"## 宫保鸡丁 [S2]\\n\\n### 食材\\n- 鸡腿肉 [S2]\\n\\n### 步骤\\n1. 炒熟 [S2]\",\"citationIds\":[\"S2\"]}";
        }, new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(),
                retrieval(recipe("r1", "番茄炒蛋"), kungPao("r2")));

        assertThat(answer.text()).startsWith("根据本轮检索，可以优先考虑宫保鸡丁 [S2]")
                .contains("需核对", "原料", "调味料", "处理状态", "## 宫保鸡丁 [S2]")
                .doesNotContain("番茄炒蛋", "食材齐全", "低脂", "替换");
        assertThat(answer.citations()).extracting(c -> c.id()).containsExactly("S2");
        assertThat(answer.diagnostics()).containsEntry("answerPurpose", "INGREDIENT_SUGGESTION")
                .containsEntry("generation", "agent");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ingredientPromptKeepsOriginalInventoryAndRequiresEvidenceForMatchesAndUnconfirmedIngredients() {
        var captured = new AtomicReference<String>();
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), prompt -> {
            captured.set(prompt);
            return "{\"refused\":true}";
        }, new ObjectMapper());

        generator.generate("s", ingredientQuery(), retrieval(kungPao("r1")));

        assertThat(captured.get()).contains(ingredientQuery().text(), "INGREDIENT_SUGGESTION", "recommendation",
                "主要食材", "还需确认／准备", "生／熟", "鲜／干", "调味料", "只有", "不要")
                .contains("不要求每道菜使用全部", "推荐说明", "[S1]");
    }

    @Test
    void ingredientSuggestionAcceptsStructuredRecommendationWithCurrentCitation() {
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), prompt ->
                "{\"recommendation\":\"可考虑宫保鸡丁；原文鸡肉可对应所述鸡腿肉，花生状态还需确认 [S1]\","
                        + "\"text\":\"## 宫保鸡丁 [S1]\\n\\n### 食材\\n- 鸡肉 [S1]\\n\\n### 步骤\\n1. 炒熟 [S1]\","
                        + "\"citationIds\":[\"S1\"]}", new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(), retrieval(kungPao("r1")));

        assertThat(answer.text()).startsWith("可考虑宫保鸡丁；原文鸡肉可对应所述鸡腿肉，花生状态还需确认 [S1]\n\n##")
                .doesNotContain("根据本轮检索");
        assertThat(answer.diagnostics()).containsEntry("generation", "agent");
    }

    @Test
    void recommendationCanCiteAnotherCurrentCandidateNotRepeatedInPrimaryRecipe() {
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), prompt ->
                "{\"recommendation\":\"可考虑宫保鸡丁 [S1]；另有番茄炒蛋，需确认其原料 [S2]\","
                        + "\"text\":\"## 宫保鸡丁 [S1]\\n\\n### 食材\\n- 鸡肉 [S1]\\n\\n### 步骤\\n1. 炒熟 [S1]\","
                        + "\"citationIds\":[\"S1\",\"S2\"]}", new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(), retrieval(kungPao("r1"), recipe("r2", "番茄炒蛋")));

        assertThat(answer.text()).startsWith("可考虑宫保鸡丁 [S1]；另有番茄炒蛋，需确认其原料 [S2]");
        assertThat(answer.citations()).extracting(c -> c.id()).containsExactly("S1", "S2");
        assertThat(answer.diagnostics()).containsEntry("generation", "agent");
    }

    @Test
    void conservativePrefaceUsesFirstRecipeCitationInTextRatherThanDeclaredCitationOrder() {
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), prompt ->
                "{\"text\":\"## 宫保鸡丁 [S2]\\n做法 [S2]；另有番茄炒蛋 [S1]\",\"citationIds\":[\"S1\",\"S2\"]}",
                new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(), retrieval(recipe("r1", "番茄炒蛋"), kungPao("r2")));

        assertThat(answer.text()).startsWith("根据本轮检索，可以优先考虑宫保鸡丁 [S2]");
        assertThat(answer.diagnostics()).containsEntry("generation", "agent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"text\":\"伪造 [S9]\",\"citationIds\":[\"S9\"]}",
            "{\"recommendation\":\"伪造推荐 [S9]\",\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"]}",
            "{\"recommendation\":\"没有引用的推荐\",\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"]}",
            "{\"recommendation\":\"伪造推荐 [S1] [S9]\",\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"]}",
            "{\"recommendation\":\"伪造推荐 [S1] [P1]\",\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"]}"})
    void invalidIngredientAnswersFallBackToCitedConservativePreface(String raw) {
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3),
                prompt -> raw, new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(), retrieval(kungPao("r1")));

        assertThat(answer.text()).startsWith("根据本轮检索，可以优先考虑宫保鸡丁 [S1]")
                .contains("需核对", "### 食材", "### 步骤").doesNotContain("S9", "伪造", "食材齐全");
        assertThat(answer.diagnostics()).containsEntry("generation", "deterministic_fallback");
    }

    @Test
    void ingredientModelExceptionFallsBackWithoutInventingInventoryMatches() {
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), prompt -> {
            throw new IllegalStateException("unavailable");
        }, new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(), retrieval(kungPao("r1")));

        assertThat(answer.text()).startsWith("根据本轮检索，可以优先考虑宫保鸡丁 [S1]")
                .contains("需核对").doesNotContain("鸡腿肉", "完全匹配", "缺少", "替换", "低脂");
        assertThat(answer.diagnostics()).containsEntry("generation", "deterministic_fallback");
    }

    @Test
    void ingredientRefusalNeverGetsRecommendationPreface() {
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3),
                prompt -> "{\"refused\":true,\"recommendation\":\"强行推荐 [S1]\"}", new ObjectMapper());

        var answer = generator.generate("s", ingredientQuery(), retrieval(kungPao("r1")));

        assertThat(answer.refused()).isTrue();
        assertThat(answer.text()).doesNotContain("优先考虑", "强行推荐", "宫保鸡丁");
        assertThat(answer.citations()).isEmpty();
    }

    private RecipeQuery ingredientQuery() {
        return new RecipeQuery("冰箱里只有鸡腿肉丁、花生米、辣椒、大葱，不要蒜，能做什么菜", List.of(), List.of(), List.of(), null);
    }

    private RecipeDocument kungPao(String id) {
        return new RecipeDocument(id, "宫保鸡丁", List.of(), List.of("鸡肉", "熟花生米", "干辣椒", "大葱", "酱油"),
                List.of("炒熟鸡肉", "加入花生米和调味料翻炒"), "", Map.of(),
                "dishes/meat_dish/宫保鸡丁.md", "commit", "hash");
    }

    @Test
    void exposesExactlyTheEvidenceBlocksSentToTheAnswerModel() {
        AtomicReference<String> prompt = new AtomicReference<>();
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(6000, 3), value -> {
                    prompt.set(value);
                    return "{\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"],\"refused\":false}";
                }, new ObjectMapper());

        var answer = generator.generate("s", query(), retrieval(recipe("r1", "番茄炒蛋"), recipe("r2", "炒鸡蛋")));

        assertThat(answer.evidenceContexts()).hasSize(2);
        assertThat(answer.evidenceContexts()).extracting(RecipeEvidenceContext::id).containsExactly("S1", "S2");
        assertThat(answer.evidenceContexts()).extracting(RecipeEvidenceContext::recipeId).containsExactly("r1", "r2");
        assertThat(answer.evidenceContexts().get(0).text()).startsWith("[S1] 菜名：番茄炒蛋");
        assertThat(answer.evidenceContexts().get(1).text()).startsWith("[S2] 菜名：炒鸡蛋");
        assertThat(prompt.get()).endsWith(answer.evidenceContexts().get(0).text() + answer.evidenceContexts().get(1).text());
        assertThat(answer.citations()).hasSize(1);
    }

    @Test
    void retainsOnlyTruncatedBlocksWithinThePromptBudget() {
        AtomicReference<String> prompt = new AtomicReference<>();
        var first = recipe("r1", "长".repeat(450));
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(600, 3), value -> {
                    prompt.set(value);
                    return "{\"text\":\"做法 [S1]\",\"citationIds\":[\"S1\"],\"refused\":false}";
                }, new ObjectMapper());

        var answer = generator.generate("s", query(), retrieval(first, recipe("r2", "炒鸡蛋"), recipe("r3", "第三道")));

        assertThat(answer.evidenceContexts()).hasSize(2);
        String sentEvidence = answer.evidenceContexts().stream().map(RecipeEvidenceContext::text).reduce("", String::concat);
        assertThat(sentEvidence).hasSize(600);
        assertThat(prompt.get()).endsWith(sentEvidence);
        assertThat(answer.evidenceContexts().get(1).text()).startsWith("[S2] 菜名：炒鸡蛋").doesNotEndWith("\n\n");
        assertThat(sentEvidence).doesNotContain("第三道");
    }

    @Test
    void retainsEvidenceForModelRefusalsAndFallbacks() {
        for (String response : List.of("{\"refused\":true}", "not json", "{\"text\":\"[S9]\",\"citationIds\":[\"S9\"]}")) {
            AtomicReference<String> prompt = new AtomicReference<>();
            var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), value -> {
                prompt.set(value);
                return response;
            }, new ObjectMapper());

            var answer = generator.generate("s", query(), retrieval(recipe("r1", "番茄炒蛋")));

            assertThat(answer.evidenceContexts()).singleElement().satisfies(context -> {
                assertThat(context.recipeId()).isEqualTo("r1");
                assertThat(prompt.get()).endsWith(context.text());
            });
            assertThat(answer.refused()).isEqualTo(response.contains("true"));
        }
    }

    @Test
    void retainsEvidenceWhenTheAnswerModelThrows() {
        AtomicReference<String> prompt = new AtomicReference<>();
        var generator = new AgentScopeRecipeAnswerGenerator(new RecipeEvidenceAssembler(6000, 3), value -> {
            prompt.set(value);
            throw new IllegalStateException("unavailable");
        }, new ObjectMapper());

        var answer = generator.generate("s", query(), retrieval(recipe("r1", "番茄炒蛋")));

        assertThat(answer.evidenceContexts()).singleElement().satisfies(context -> assertThat(prompt.get()).endsWith(context.text()));
        assertThat(answer.diagnostics()).containsEntry("generation", "deterministic_fallback");
    }

    @Test
    void defensivelyCopiesEvidenceAndPreservesLegacyConstruction() {
        var contexts = new ArrayList<>(List.of(new RecipeEvidenceContext("S1", "r1", "evidence")));
        var answer = new RecipeAnswer("answer", List.of(), false, Map.of(), contexts);
        var refused = RecipeAnswer.refused("refused", Map.of(), contexts);
        contexts.clear();

        assertThat(answer.evidenceContexts()).hasSize(1);
        assertThat(refused.evidenceContexts()).hasSize(1);
        assertThatThrownBy(() -> answer.evidenceContexts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> refused.evidenceContexts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new RecipeAnswer("legacy", List.of(), false, Map.of()).evidenceContexts()).isEmpty();
        assertThat(new RecipeAnswer("null", null, false, null, null).evidenceContexts()).isEmpty();
        assertThat(RecipeAnswer.refused("legacy", Map.of()).evidenceContexts()).isEmpty();
    }

    @Test
    void evidenceBundleDefensivelyCopiesAllLists() {
        var original = new RecipeEvidenceAssembler(6000, 3).assemble(retrieval(recipe("r1", "番茄炒蛋")));
        var contexts = new ArrayList<>(original.contexts());
        var citations = new ArrayList<>(original.citations());
        var documents = new ArrayList<>(original.documents());
        var bundle = new RecipeEvidenceAssembler.EvidenceBundle(original.promptEvidence(), contexts, citations, documents);
        contexts.clear();
        citations.clear();
        documents.clear();

        assertThat(bundle.contexts()).hasSize(1);
        assertThat(bundle.citations()).hasSize(1);
        assertThat(bundle.documents()).hasSize(1);
        assertThatThrownBy(() -> bundle.contexts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bundle.citations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bundle.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        var empty = new RecipeEvidenceAssembler.EvidenceBundle("", null, null, null);
        assertThat(empty.contexts()).isEmpty();
        assertThat(empty.citations()).isEmpty();
        assertThat(empty.documents()).isEmpty();
    }

    @Test
    void contextValidatesIdentityWithoutAlteringEvidenceText() {
        assertThat(new RecipeEvidenceContext(" S1 ", " r1 ", " evidence\n")).isEqualTo(new RecipeEvidenceContext("S1", "r1", " evidence\n"));
        assertThatThrownBy(() -> new RecipeEvidenceContext(null, "r1", "evidence")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecipeEvidenceContext("S1", " ", "evidence")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecipeEvidenceContext("S1", "r1", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyCitationsFromCurrentEvidence() {
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(6000, 3),
                prompt -> "{\"text\":\"先炒鸡蛋，再炒番茄 [S1]\",\"citationIds\":[\"S1\"],\"refused\":false}",
                new ObjectMapper());

        var answer = generator.generate("s", new RecipeQuery("番茄炒蛋怎么做", List.of(), List.of(), List.of(), null),
                retrieval(recipe("r1", "番茄炒蛋")));

        assertThat(answer.refused()).isFalse();
        assertThat(answer.text()).contains("[S1]");
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.id()).isEqualTo("S1");
            assertThat(citation.sourcePath()).isEqualTo("dishes/vegetable_dish/番茄炒蛋.md");
        });
    }

    @Test
    void fallsBackToDeterministicEvidenceWhenModelInventsCitation() {
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(6000, 3),
                prompt -> "{\"text\":\"加入不存在的步骤 [S9]\",\"citationIds\":[\"S9\"],\"refused\":false}",
                new ObjectMapper());

        var answer = generator.generate("s", new RecipeQuery("番茄炒蛋", List.of(), List.of(), List.of(), null),
                retrieval(recipe("r1", "番茄炒蛋")));

        assertThat(answer.refused()).isFalse();
        assertThat(answer.text()).contains("番茄", "鸡蛋", "[S1]").doesNotContain("S9");
        assertThat(answer.diagnostics()).containsEntry("generation", "deterministic_fallback");
    }

    @Test
    void deterministicFallbackUsesStableMarkdownSections() {
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(6000, 3),
                prompt -> "{\"text\":\"引用不存在 [S9]\",\"citationIds\":[\"S9\"],\"refused\":false}",
                new ObjectMapper());

        var answer = generator.generate("s", new RecipeQuery("番茄炒蛋", List.of(), List.of(), List.of(), null),
                retrieval(recipe("r1", "番茄炒蛋")));

        assertThat(answer.text())
                .startsWith("## 番茄炒蛋 [S1]")
                .contains("### 食材\n- 番茄 2 个 [S1]")
                .contains("### 步骤\n1. 鸡蛋炒熟盛出 [S1]")
                .contains("### 来源\n- [S1]");
    }

    @Test
    void asksAnswerModelForStableMarkdownSections() {
        AtomicReference<String> prompt = new AtomicReference<>();
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(6000, 3),
                value -> {
                    prompt.set(value);
                    return "{\"text\":\"回答 [S1]\",\"citationIds\":[\"S1\"],\"refused\":false}";
                },
                new ObjectMapper());

        generator.generate("s", new RecipeQuery("番茄炒蛋", List.of(), List.of(), List.of(), null),
                retrieval(recipe("r1", "番茄炒蛋")));

        assertThat(prompt.get()).contains("### 食材", "### 步骤", "Markdown");
    }

    @Test
    void refusesWhenThereIsNoEvidence() {
        var generator = new AgentScopeRecipeAnswerGenerator(
                new RecipeEvidenceAssembler(6000, 3), prompt -> "should not run", new ObjectMapper());

        var answer = generator.generate("s", new RecipeQuery("量子计算机怎么做", List.of(), List.of(), List.of(), null),
                retrieval());

        assertThat(answer.refused()).isTrue();
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.evidenceContexts()).isEmpty();
    }

    private RecipeQuery query() {
        return new RecipeQuery("番茄炒蛋怎么做", List.of(), List.of(), List.of(), null);
    }

    private RecipeRetrievalResult retrieval(RecipeDocument... documents) {
        var recipes = List.of(documents).stream().map(document -> new RetrievedRecipe(document, 1, List.of())).toList();
        return new RecipeRetrievalResult(recipes, new RecipeRetrievalResult.Diagnostics(
                RecipeRetrievalMode.PARALLEL_HYBRID, RecipeRetrievalMode.PARALLEL_HYBRID,
                1, 1, 1, recipes.size(), recipes.size(), List.of(),
                recipes.stream().map(value -> value.recipe().recipeId()).toList()));
    }

    private RecipeDocument recipe(String id, String title) {
        return new RecipeDocument(id, title, List.of("西红柿炒鸡蛋"), List.of("番茄 2 个", "鸡蛋 3 个"),
                List.of("鸡蛋炒熟盛出", "番茄炒软后与鸡蛋混合"), "家常菜", Map.of(),
                "dishes/vegetable_dish/番茄炒蛋.md", "commit", "hash");
    }
}
