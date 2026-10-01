package com.diet.evaluation.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeAnswerMetricsTest {
    @Test
    void measuresCitationCoverageAndEvidenceSupportWithoutAnOpaqueTotalScore() {
        String answer = "食材包括番茄和鸡蛋 [S1]。先炒鸡蛋 [S1]，再加入番茄 [S1]。";
        assertThat(RecipeAnswerMetrics.citationCoverage(List.of("番茄", "先炒鸡蛋"), answer)).isEqualTo(1.0);
        assertThat(RecipeAnswerMetrics.supportedFactRate(
                List.of("番茄", "先炒鸡蛋", "加入蜂蜜"), List.of("番茄", "鸡蛋", "先炒鸡蛋", "加入番茄")))
                .isEqualTo(2.0 / 3.0);
    }

    @Test
    void measuresRefusalAccuracyIndependently() {
        assertThat(RecipeAnswerMetrics.refusalAccuracy(List.of(true, false, true), List.of(true, false, false)))
                .isEqualTo(2.0 / 3.0);
    }
}
