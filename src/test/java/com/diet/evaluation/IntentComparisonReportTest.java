package com.diet.evaluation;

import com.diet.enums.Intent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentComparisonReportTest {
    @Test
    void failedCallsStayInAccuracyDenominatorAndDoNotBecomeOtherPredictions() {
        var results = List.of(
                row("one", "BASELINE", Intent.RECIPE_QUERY, Intent.RECIPE_QUERY, "OK", 100),
                row("two", "BASELINE", Intent.OTHER, null, "ERROR", 300));
        var metric = IntentComparisonReport.summarize(results);
        assertThat(metric.attempts()).isEqualTo(2);
        assertThat(metric.correct()).isEqualTo(1);
        assertThat(metric.accuracy()).isEqualTo(50.0);
        assertThat(metric.failures()).isEqualTo(1);
        assertThat(metric.successOnlyAccuracy()).isEqualTo(100.0);
        assertThat(metric.confusion().get("OTHER").get("NO_PREDICTION")).isEqualTo(1L);
        assertThat(metric.latencyP95Ms()).isEqualTo(300);
    }

    @Test
    void distinguishesRawJevErrorsFromRecoveredChainAndShowsPairedWins() {
        var results = List.of(
                row("one", "BASELINE", Intent.RECIPE_QUERY, Intent.OTHER, "OK", 100),
                row("one", "JEV_RAW", Intent.RECIPE_QUERY, null, "ERROR", 200),
                row("one", "JEV_CHAIN", Intent.RECIPE_QUERY, Intent.RECIPE_QUERY, "OK", 350));
        var report = IntentComparisonReport.build(results);
        assertThat(report.get("main/JEV_RAW").accuracy()).isZero();
        assertThat(report.get("main/JEV_CHAIN").accuracy()).isEqualTo(100.0);
        assertThat(IntentComparisonReport.paired(results, "main", "JEV_CHAIN").wins()).isEqualTo(1);
        assertThat(IntentComparisonReport.paired(results, "main", "JEV_RAW").wins()).isZero();
    }

    @Test
    void challengeResultsDoNotChangeFrozenMainDatasetScore() {
        var main = row("one", "BASELINE", Intent.RECIPE_QUERY, Intent.RECIPE_QUERY, "OK", 100);
        var hard = new IntentComparisonReport.Row("hard", "challenge", "BASELINE", 1,
                "ingredient_reverse", "口语问题", Intent.RECIPE_QUERY, Intent.RECIPE_QUERY,
                Intent.OTHER, Intent.OTHER, 0.95, "OK", "DEEPSEEK", null, 100, 1, null, null);
        var report = IntentComparisonReport.build(List.of(main, hard));
        assertThat(report.get("main/BASELINE").accuracy()).isEqualTo(100.0);
        assertThat(report.get("challenge/BASELINE").accuracy()).isZero();
    }

    @Test
    void calibrationUsesRawIntentAndReportsHighConfidenceErrors() {
        var metric = IntentComparisonReport.summarize(List.of(
                row("one", "BASELINE", Intent.RECIPE_QUERY, Intent.OTHER, "OK", 100)));
        assertThat(metric.highConfidenceErrors()).isEqualTo(1);
        assertThat(metric.calibrationCount()).isEqualTo(1);
        assertThat(metric.ece()).isEqualTo(0.9);
    }

    @Test
    void summarizesVariationAcrossRunsWithoutSelectingBestRun() {
        var first = row("one", "BASELINE", Intent.RECIPE_QUERY, Intent.RECIPE_QUERY, "OK", 100);
        var second = new IntentComparisonReport.Row("one", "main", "BASELINE", 2, "direct", "示例问题",
                Intent.RECIPE_QUERY, Intent.RECIPE_QUERY, Intent.OTHER, Intent.OTHER, 0.9,
                "OK", "DEEPSEEK", null, 100, 1, null, null);
        var variation = IntentComparisonReport.repeatVariation(List.of(first, second)).get("main/BASELINE");
        assertThat(variation.runs()).isEqualTo(2);
        assertThat(variation.meanAccuracy()).isEqualTo(50);
        assertThat(variation.populationStdDev()).isEqualTo(50);
        assertThat(variation.worstAccuracy()).isZero();
    }

    @Test
    void absentComparisonDoesNotInventZeroPercentagePointDifference() {
        var paired = IntentComparisonReport.paired(List.of(
                row("one", "BASELINE", Intent.RECIPE_QUERY, Intent.RECIPE_QUERY, "OK", 100)), "main", "JEV_RAW");
        assertThat(paired.pairs()).isZero();
        assertThat(paired.deltaAccuracyPp()).isNull();
    }

    private static IntentComparisonReport.Row row(String id, String arm, Intent expected,
                                                   Intent predicted, String status, long latency) {
        return new IntentComparisonReport.Row(id, "main", arm, 1, "direct", "示例问题",
                expected, expected, predicted, predicted, predicted == null ? null : 0.9,
                status, arm.equals("BASELINE") ? "DEEPSEEK" : "JEV", null, latency,
                1, null, null);
    }
}
