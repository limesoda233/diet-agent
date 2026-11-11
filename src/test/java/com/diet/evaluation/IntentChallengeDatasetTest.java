package com.diet.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class IntentChallengeDatasetTest {
    @Test
    void supplementaryLabelsAreValidUniqueAndHaveExplicitContextForAdjustments() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (var in = getClass().getClassLoader().getResourceAsStream("evaluation/intent-challenge-v1.jsonl")) {
            assertThat(in).isNotNull();
            var cases = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank())
                    .map(s -> { try { return json.readValue(s, IntentEvaluationDataset.Case.class); }
                        catch (Exception e) { throw new IllegalStateException("Invalid challenge case", e); } }).toList();
            assertThat(cases).hasSize(16);
            var ids = new HashSet<>(IntentEvaluationDataset.load(json).stream().map(IntentEvaluationDataset.Case::id).toList());
            for (var c : cases) {
                assertThat(ids.add(c.id())).isTrue();
                assertThat(c.utterance()).isNotBlank(); assertThat(c.slice()).isNotBlank();
                assertThat(c.expectedAgentIntent()).isNotNull(); assertThat(c.expectedFinalIntent()).isNotNull();
                if (c.hasPreviousRecommendations()) assertThat(c.history()).isNotEmpty();
            }
        }
    }
}
