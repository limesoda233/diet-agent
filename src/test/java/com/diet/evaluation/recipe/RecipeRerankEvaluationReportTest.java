package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.retrieval.RecipeRerankResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeRerankEvaluationReportTest {
    @Test
    void testSplitRequiresExplicitFreezeThresholdAndCalibrationIdentity() {
        assertThat(RecipeRerankEvaluationReport.selectExamples("dev", false, "", null)).hasSize(60);
        assertThatThrownBy(() -> RecipeRerankEvaluationReport.selectExamples("test", false, "", 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecipeRerankEvaluationReport.selectExamples("test", true, "dev-run", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RecipeRerankEvaluationReport.selectExamples("test", true, "dev-run", 0.5)).hasSize(30);
    }

    @Test
    void pairedReportSeparatesScorerFailureFromCorrectRejectionAndPreservesGoldForUnsupportedFacts() {
        var dataset = RecipeEvaluationDataset.load("v2");
        var positive = dataset.examples().stream().filter(e -> "dev".equals(e.split()) && e.retrievalExpectedRelevant()).findFirst().orElseThrow();
        var empty = dataset.examples().stream().filter(e -> "dev".equals(e.split()) && e.retrievalExpectedEmpty()).findFirst().orElseThrow();
        String gold = positive.relevantRecipeIds().get(0);
        var rows = List.of(
                row(positive.queryId(), List.of("noise", gold), RecipeRerankResult.Status.ACCEPTED,
                        List.of(new RecipeRerankResult.CandidateScore(gold, 0.02, 0.9), new RecipeRerankResult.CandidateScore("noise", 0.03, 0.1))),
                row(empty.queryId(), List.of("noise"), RecipeRerankResult.Status.ACCEPTED,
                        List.of(new RecipeRerankResult.CandidateScore("noise", 0.03, 0.1))),
                new RecipeRerankEvaluationReport.Observation(empty.queryId(), 2, List.of("noise"), 20,
                        false, RecipeRerankResult.Status.UNAVAILABLE, List.of(), 100, "http_500", "", List.of()));
        Map<String,Object> report = RecipeRerankEvaluationReport.build(Map.of("plannedObservations", 3), rows, 0.5);
        assertThat(report).containsEntry("scoringFailures", 1).containsEntry("pairedObservations", 2)
                .containsEntry("comparisonReady", false);
        @SuppressWarnings("unchecked")
        Map<String, Map<String,Object>> metrics = (Map<String, Map<String,Object>>) report.get("pairedMetrics");
        assertThat(metrics.get("BASELINE")).containsEntry("emptyExpectationAccuracy", 0.0);
        assertThat(metrics.get("FILTERED")).containsEntry("emptyExpectationAccuracy", 1.0)
                .containsEntry("positiveFalseRejectionRate", 0.0);
        assertThat(metrics.get("RERANK_ONLY").get("mrrAt10")).isEqualTo(1.0);
    }

    @Test
    void degradedEmptyRetrievalIsNotCountedAsSuccessfulOodRejection() {
        var empty = RecipeEvaluationDataset.load("v2").examples().stream()
                .filter(e -> "dev".equals(e.split()) && e.retrievalExpectedEmpty()).findFirst().orElseThrow();
        var row = new RecipeRerankEvaluationReport.Observation(empty.queryId(), 1, List.of(), 20,
                false, RecipeRerankResult.Status.FILTERED, List.of(), 0, "", "", List.of(),
                "BM25_ONLY", List.of("dense_failed:timeout"));
        var report = RecipeRerankEvaluationReport.build(Map.of("plannedObservations", 1), List.of(row), 0.5);
        assertThat(report).containsEntry("retrievalDegradations", 1).containsEntry("pairedObservations", 0)
                .containsEntry("scoringFailures", 0).containsEntry("comparisonReady", false);
        @SuppressWarnings("unchecked")
        var metrics = (Map<String, Map<String,Object>>) report.get("pairedMetrics");
        assertThat(metrics.get("FILTERED").get("emptyExpectationAccuracy")).isNull();
    }

    @Test
    void noThresholdMeansCollectionOnlyAndDoesNotInventFilteredMetrics() {
        var report = RecipeRerankEvaluationReport.build(Map.of("plannedObservations", 0), List.of(), null);
        @SuppressWarnings("unchecked")
        var metrics = (Map<String,Object>) report.get("pairedMetrics");
        assertThat(metrics).doesNotContainKey("FILTERED");
        assertThat(report).containsEntry("comparisonReady", false);
    }

    private RecipeRerankEvaluationReport.Observation row(String id, List<String> candidates,
                                                         RecipeRerankResult.Status status,
                                                         List<RecipeRerankResult.CandidateScore> scores) {
        return new RecipeRerankEvaluationReport.Observation(id, 1, candidates, 20, false,
                status, scores, 30, "", "", List.of());
    }
}
