package com.diet.evaluation.recipe;

import com.diet.mapper.MealMapper;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.recipe.RecipeQueryService;
import com.diet.service.recipe.rag.index.RecipeIndexCoordinator;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "diet.recipe-rag.enabled=true",
        "spring.datasource.hikari.initialization-fail-timeout=-1",
        "spring.datasource.hikari.minimum-idle=0"
})
@EnabledIfSystemProperty(named = "diet.recipe-ragas.export.live", matches = "true")
class RecipeAnswerEvaluationExportTest {
    @Autowired RecipeQueryService recipes;
    @Autowired RecipeIndexCoordinator index;
    @Autowired ObjectMapper json;
    @MockBean MealKnowledgeRepository knowledgeRepository;
    @MockBean MealMapper mealMapper;
    @Value("${diet.llm.main-model:deepseek-chat}") String generatorModel;
    @Value("${agentscope.deepseek.api-key:}") String apiKey;

    @Test
    void exportsEveryLockedTestAnswerThroughProductionRag() throws Exception {
        assertThat(apiKey != null && !apiKey.isBlank())
                .as("Live export requires agentscope.deepseek.api-key, the pinned HowToCook checkout, "
                        + "Ollama embeddings, Qdrant and the configured main chat model")
                .isTrue();
        String corpusPath = System.getProperty("diet.recipe-rag.corpus-path");
        assertThat(corpusPath).as("Provide -Ddiet.recipe-rag.corpus-path for the pinned HowToCook checkout")
                .isNotBlank();
        index.rebuild(Path.of(corpusPath));
        RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load("v2");
        var lockedTest = dataset.examples().stream().filter(example -> "test".equals(example.split())).toList();
        assertThat(lockedTest).isNotEmpty();
        List<RecipeRagasSample> samples = new ArrayList<>();
        Map<String, Long> generationModes = new LinkedHashMap<>();
        for (var example : lockedTest) {
            long started = System.nanoTime();
            RecipeAnswer answer = recipes.answer("ragas-" + example.queryId(), 1L,
                    example.query(), context(example));
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            samples.add(sample(dataset, example, answer, generatorModel, totalMs));
            generationModes.merge(String.valueOf(answer.diagnostics().getOrDefault("generation", "refused")),
                    1L, Long::sum);
        }
        assertThat(samples).hasSize(lockedTest.size());
        assertThat(samples.stream().map(RecipeRagasSample::queryId).toList())
                .containsExactlyInAnyOrderElementsOf(dataset.testIds());
        // Check the serialized payload before replacing any previous complete export. Never expose the key in failures.
        assertThat(json.writeValueAsString(samples).contains(apiKey))
                .as("The export must not contain the main-model API key").isFalse();
        Path output = Path.of("target/evaluation/recipe/ragas-input.jsonl");
        new RecipeRagasExporter(json).write(output, samples);
        try (var lines = Files.lines(output)) {
            assertThat(lines.count()).isEqualTo(lockedTest.size());
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output)));
        System.out.printf("Ragas export: samples=%d, generator=%s, generation=%s, sha256=%s, file=%s%n",
                samples.size(), generatorModel, generationModes, hash, output.toAbsolutePath());
    }

    static Map<String, Object> context(RecipeEvaluationDataset.Example example) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("query", example.query());
        query.put("requiredIngredients", example.requiredIngredients());
        query.put("excludedIngredients", example.excludedIngredients());
        query.put("requiredTools", example.requiredTools());
        if (example.maxMinutes() != null) query.put("maxMinutes", example.maxMinutes());
        return Map.of("recipeQuery", query);
    }

    static RecipeRagasSample sample(RecipeEvaluationDataset dataset, RecipeEvaluationDataset.Example example,
                                   RecipeAnswer answer, String generatorModel, long totalMs) {
        assertThat(answer.diagnostics().get("route")).as("Retrieval must succeed for %s", example.queryId())
                .isNotEqualTo("retrieval_failed");
        if (!answer.refused()) {
            assertThat(answer.evidenceContexts()).as("Final answer evidence for %s", example.queryId()).isNotEmpty();
        }
        Object latency = answer.diagnostics().get("retrievalDurationMs");
        long retrievalMs = latency instanceof Number number ? Math.max(0, number.longValue()) : 0;
        return RecipeRagasSample.from(dataset, example, answer, generatorModel,
                retrievalMs, Math.max(0, totalMs - retrievalMs));
    }
}

class RecipeAnswerEvaluationExportContractTest {
    @Test
    void preservesEveryEvaluationConstraintWithoutChangingTheQuestion() {
        var example = example(25);
        assertThat(RecipeAnswerEvaluationExportTest.context(example)).isEqualTo(Map.of("recipeQuery", Map.of(
                "query", "隔夜米饭怎么做",
                "requiredIngredients", List.of("米饭"),
                "excludedIngredients", List.of("花生"),
                "requiredTools", List.of("炒锅"),
                "maxMinutes", 25)));
    }

    @Test
    void omitsTheTimeLimitWhenTheExampleHasNoLimit() {
        assertThat(((Map<?, ?>) RecipeAnswerEvaluationExportTest.context(example(null)).get("recipeQuery")).get("requiredTools"))
                .isEqualTo(List.of("炒锅"));
        assertThat(((Map<?, ?>) RecipeAnswerEvaluationExportTest.context(example(null)).get("recipeQuery")).containsKey("maxMinutes")).isFalse();
    }

    @Test
    void exportsFinalEvidenceMainGeneratorAndObservedTimings() {
        var answer = new RecipeAnswer("做法 [S1]", List.of(new RecipeCitation("S1", "recipe-1", "炒饭", "rice.md", "commit", "hash")),
                false, Map.of("retrievalDurationMs", 12L), List.of(new RecipeEvidenceContext("S1", "recipe-1", "证据")));
        var sample = RecipeAnswerEvaluationExportTest.sample(RecipeEvaluationDataset.load("v2"), example(25),
                answer, "main-chat-model", 35);
        assertThat(sample.generatorModel()).isEqualTo("main-chat-model");
        assertThat(sample.retrievedContexts()).containsExactly("证据");
        assertThat(sample.retrievedContextIds()).containsExactly("recipe-1");
        assertThat(sample.retrievalLatencyMs()).isEqualTo(12);
        assertThat(sample.generationLatencyMs()).isEqualTo(23);
    }

    @Test
    void rejectsInfrastructureFailureInsteadOfExportingItAsARefusal() {
        var answer = RecipeAnswer.refused("稍后再试", Map.of("route", "retrieval_failed"));
        assertThatThrownBy(() -> RecipeAnswerEvaluationExportTest.sample(RecipeEvaluationDataset.load("v2"),
                example(null), answer, "main-model", 5)).isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsAnAnsweredCaseWithoutFinalEvidence() {
        var answer = new RecipeAnswer("无证据回答", List.of(), false, Map.of());
        assertThatThrownBy(() -> RecipeAnswerEvaluationExportTest.sample(RecipeEvaluationDataset.load("v2"),
                example(null), answer, "main-model", 5)).isInstanceOf(AssertionError.class);
    }

    @Test
    void allowsAnEvidenceFreeRefusalAndClampsResidualGenerationTime() {
        var answer = RecipeAnswer.refused("没有证据", Map.of("retrievalDurationMs", 20));
        var sample = RecipeAnswerEvaluationExportTest.sample(RecipeEvaluationDataset.load("v2"), example(null),
                answer, "main-model", 10);
        assertThat(sample.refused()).isTrue();
        assertThat(sample.retrievedContexts()).isEmpty();
        assertThat(sample.retrievalLatencyMs()).isEqualTo(20);
        assertThat(sample.generationLatencyMs()).isZero();
    }

    private RecipeEvaluationDataset.Example example(Integer maxMinutes) {
        return new RecipeEvaluationDataset.Example("export-fixture", "隔夜米饭怎么做", "ingredient", "test",
                RecipeEvaluationDataset.RetrievalExpectation.RELEVANT, RecipeEvaluationDataset.AnswerExpectation.ANSWER,
                List.of("米饭"), List.of("花生"), List.of("炒锅"), maxMinutes, List.of("recipe-1"), Map.of("recipe-1", 3),
                List.of(), List.of(), List.of("rice.md"), "fixture");
    }
}
