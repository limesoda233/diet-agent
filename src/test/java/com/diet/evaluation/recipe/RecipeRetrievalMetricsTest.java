package com.diet.evaluation.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeRetrievalMetricsTest {
    @Test
    void computesRecallMrrAndGradedNdcg() {
        assertThat(RecipeRetrievalMetrics.recallAt(Set.of("a", "b"), List.of("a", "c"), 2)).isEqualTo(0.5);
        assertThat(RecipeRetrievalMetrics.reciprocalRankAt(Set.of("b"), List.of("a", "b", "c"), 10)).isEqualTo(0.5);
        assertThat(RecipeRetrievalMetrics.ndcgAt(Map.of("a", 3, "b", 1), List.of("b", "a"), 2))
                .isBetween(0.7, 1.0);
    }

    @Test
    void reportsParentDuplicatesAndEmptyResultsSeparately() {
        assertThat(RecipeRetrievalMetrics.duplicateRate(List.of("a", "a", "b"))).isEqualTo(1.0 / 3.0);
        assertThat(RecipeRetrievalMetrics.emptyResultRate(List.of(List.of(), List.of("a")))).isEqualTo(0.5);
    }
}
