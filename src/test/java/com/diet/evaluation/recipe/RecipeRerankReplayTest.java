package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.retrieval.RecipeRerankResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end offline replay: no API key, corpus checkout, embedding or database is needed. */
class RecipeRerankReplayTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void devReplayWritesNewReportAndPreservesSourceWithoutAnyExternalDependency() throws Exception {
        Path source = fixture("dev", 2);
        String original = Files.readString(source);
        Path output = new RecipeRerankEvaluationTest().replay(source, 0.5, false, "");
        assertThat(Files.readString(source)).isEqualTo(original);
        var report = json.readTree(output.toFile());
        assertThat(report.path("comparisonReady").asBoolean()).isTrue();
        assertThat(report.path("pairedMetrics").path("FILTERED").path("recallAt1").asDouble()).isEqualTo(1.0);
        assertThat(report.path("metadata").path("sourceReportSha256").asText()).hasSize(64);
        assertThat(report.path("metadata").path("mode").asText()).contains("no_new_latency");
    }

    @Test
    void lockedTestReplayCannotChangeRecordedThreshold() throws Exception {
        Path source = fixture("test", 2);
        assertThatThrownBy(() -> new RecipeRerankEvaluationTest().replay(source, 0.6, true, "dev-run"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cannot tune");
    }

    @Test
    void legacyReportWithoutRetrievalHealthIsNotSilentlyTreatedAsHealthy() throws Exception {
        Path source = fixture("dev", 1);
        assertThatThrownBy(() -> new RecipeRerankEvaluationTest().replay(source, 0.5, false, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema 2");
    }

    private Path fixture(String split, int schema) throws Exception {
        var dataset = RecipeEvaluationDataset.load("v2");
        var example = dataset.examples().stream().filter(value -> split.equals(value.split())
                && value.retrievalExpectedRelevant()).findFirst().orElseThrow();
        String gold = example.relevantRecipeIds().get(0);
        var row = new RecipeRerankEvaluationReport.Observation(example.queryId(), 1, List.of(gold), 12,
                false, RecipeRerankResult.Status.ACCEPTED,
                List.of(new RecipeRerankResult.CandidateScore(gold, 0.03, 0.9)), 25, "", "query-hash", List.of("doc-hash"));
        Path path = temporary.resolve("source.json");
        json.writeValue(path.toFile(), Map.of("metadata", Map.of("reportSchemaVersion", schema,
                "runId", "fixture", "split", split, "datasetHash", dataset.fingerprint(),
                "plannedObservations", 1, "frozenThreshold", 0.5), "rows", List.of(row)));
        return path;
    }
}
