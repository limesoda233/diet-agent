package com.diet.service.jev;

import com.diet.model.EvaluationJudgeResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JevEvaluationJudgeTest {

    @Test
    void convertsZeroBasedJevScoresToExistingOneToFiveScale() {
        JevClient client = (state, questions) -> new JevResponse(
                "jev-1.13.0",
                Map.of(
                        "explanation_quality", score(2.5, 0.72),
                        "naturalness", score(4.0, 0.91)
                ),
                new JevUsage(50, 10)
        );
        JevProperties properties = new JevProperties();
        properties.setApiKey("test-key");
        properties.setEnabled(true);
        properties.setEvaluationEnabled(true);
        JevEvaluationJudge judge = new JevEvaluationJudge(client, properties);

        EvaluationJudgeResult result = judge.judge(Map.of(
                "userMessage", "晚饭想吃清淡一点",
                "finalReply", "推荐清蒸鱼，符合清淡口味。"
        ));

        assertThat(result.explanationQuality()).isEqualTo(3.5);
        assertThat(result.naturalness()).isEqualTo(5.0);
        assertThat(result.reason()).contains("jev-1.13.0").contains("0.72").contains("0.91");
    }

    private static JevAnswer score(double value, double confidence) {
        return new JevAnswer("score", null, value, null, confidence, Map.of());
    }
}
