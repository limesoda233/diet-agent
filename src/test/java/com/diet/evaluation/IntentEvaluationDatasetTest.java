package com.diet.evaluation;

import com.diet.enums.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationDatasetTest {

    @Test
    void goldDatasetIsValidBalancedAndContextAware() {
        List<IntentEvaluationDataset.Case> cases = IntentEvaluationDataset.load(new ObjectMapper());

        assertThat(cases).hasSize(84);
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(testCase.id()).isNotBlank();
            assertThat(testCase.slice()).isNotBlank();
            assertThat(testCase.utterance()).isNotBlank();
            assertThat(testCase.expectedAgentIntent()).isNotNull();
            assertThat(testCase.expectedFinalIntent()).isNotNull();
        });
        assertThat(new HashSet<>(cases.stream().map(IntentEvaluationDataset.Case::id).toList()))
                .hasSize(cases.size());

        Map<Intent, Long> finalLabelCounts = new EnumMap<>(Intent.class);
        cases.forEach(testCase -> finalLabelCounts.merge(testCase.expectedFinalIntent(), 1L, Long::sum));
        assertThat(finalLabelCounts).containsOnlyKeys(Intent.values());
        assertThat(finalLabelCounts.values()).allMatch(count -> count == 12L);

        assertThat(cases.stream()
                .filter(testCase -> testCase.expectedFinalIntent() == Intent.MEAL_ADJUST)
                .allMatch(IntentEvaluationDataset.Case::hasPreviousRecommendations)).isTrue();
        assertThat(cases.stream()
                .filter(testCase -> testCase.hasPreviousRecommendations())
                .allMatch(testCase -> testCase.history() != null && !testCase.history().isEmpty())).isTrue();
    }
}
