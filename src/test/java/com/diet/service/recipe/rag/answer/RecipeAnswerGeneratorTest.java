package com.diet.service.recipe.rag.answer;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeAnswerGeneratorTest {

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
