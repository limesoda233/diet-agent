package com.diet.service.recipe.rag.answer;

import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RecipeRetrievalResult;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeAnswerGeneratorTest {

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
