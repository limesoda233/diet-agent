package com.diet.service.jev;

import com.diet.model.EvaluationJudgeResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class JevEvaluationJudge {
    private static final List<String> EXPLANATION_LEVELS = List.of(
            "没有解释推荐原因，或理由与用户请求和候选信息无关",
            "有笼统理由，但没有对应用户的具体需求或餐食信息",
            "部分理由对应用户需求或候选信息，但信息不完整",
            "主要推荐理由都有用户需求或候选信息支持，解释清楚",
            "理由具体、完整且可核对，清楚说明每项推荐为什么适合用户"
    );
    private static final List<String> NATURALNESS_LEVELS = List.of(
            "表达明显破碎、机械或难以理解",
            "能够理解，但有较多生硬表单式表达或重复",
            "整体通顺，仍有少量机械或冗余表达",
            "简洁自然，符合饮食助手对话语气",
            "非常自然、得体且信息组织清晰"
    );

    private final JevClient client;
    private final JevProperties properties;

    public JevEvaluationJudge(JevClient client, JevProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean available() {
        return properties.configured() && properties.isEvaluationEnabled();
    }

    public EvaluationJudgeResult judge(Map<String, Object> judgeInput) {
        JevResponse response = client.systemOne(judgeInput, Map.of(
                "explanation_quality", Map.of(
                        "type", "score",
                        "instructions", "只根据 `userMessage`、`finalReply`、`predictedSlots` 和 `rankedCandidates` 评价推荐解释质量。不要评价自然度。",
                        "criteria", EXPLANATION_LEVELS
                ),
                "naturalness", Map.of(
                        "type", "score",
                        "instructions", "只评价 `finalReply` 的语言自然度。不要评价推荐是否正确。",
                        "criteria", NATURALNESS_LEVELS
                )
        ));
        JevAnswer explanation = scoreAnswer(response, "explanation_quality");
        JevAnswer naturalness = scoreAnswer(response, "naturalness");
        double explanationScore = oneToFive(explanation.score());
        double naturalnessScore = oneToFive(naturalness.score());
        String reason = String.format(
                Locale.ROOT,
                "%s structured scores; explanation confidence %.2f, naturalness confidence %.2f",
                response.model(),
                explanation.confidence(),
                naturalness.confidence()
        );
        return new EvaluationJudgeResult(explanationScore, naturalnessScore, reason);
    }

    private JevAnswer scoreAnswer(JevResponse response, String id) {
        JevAnswer answer = response == null || response.answers() == null ? null : response.answers().get(id);
        if (answer == null || !"score".equals(answer.type()) || answer.score() == null || answer.confidence() == null) {
            throw new JevClientException("jev_score_answer_invalid_" + id);
        }
        return answer;
    }

    private double oneToFive(double zeroBasedScore) {
        if (!Double.isFinite(zeroBasedScore)) {
            throw new JevClientException("jev_score_invalid");
        }
        return Math.max(1.0, Math.min(5.0, zeroBasedScore + 1.0));
    }
}
