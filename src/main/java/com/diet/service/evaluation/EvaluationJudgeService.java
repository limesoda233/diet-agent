package com.diet.service.evaluation;

import com.diet.agent.builder.EvaluationJudgeAgentBuilder;
import com.diet.model.EvaluationJudgeResult;
import com.diet.service.jev.JevEvaluationJudge;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class EvaluationJudgeService {
    private static final Logger log = LoggerFactory.getLogger(EvaluationJudgeService.class);

    private final EvaluationJudgeAgentBuilder agentBuilder;
    private final AgentTraceService agentTraceService;
    private final LlmJsonService llmJsonService;
    private final ObjectMapper objectMapper;
    private final JevEvaluationJudge jevEvaluationJudge;
    private final String modelName;

    public EvaluationJudgeService(
            EvaluationJudgeAgentBuilder agentBuilder,
            AgentTraceService agentTraceService,
            LlmJsonService llmJsonService,
            ObjectMapper objectMapper,
            JevEvaluationJudge jevEvaluationJudge,
            @Value("${diet.llm.light-model:deepseek-chat}") String modelName
    ) {
        this.agentBuilder = agentBuilder;
        this.agentTraceService = agentTraceService;
        this.llmJsonService = llmJsonService;
        this.objectMapper = objectMapper;
        this.jevEvaluationJudge = jevEvaluationJudge;
        this.modelName = modelName;
    }

    public EvaluationJudgeResult judge(String traceId, String sessionId, Map<String, Object> judgeInput) {
        if (jevEvaluationJudge.available()) {
            long startedAt = System.nanoTime();
            try {
                EvaluationJudgeResult result = jevEvaluationJudge.judge(judgeInput);
                agentTraceService.recordEvent(
                        "JEV_EVALUATION_JUDGE",
                        "EVALUATION",
                        judgeInput,
                        result,
                        elapsedMs(startedAt)
                );
                return result;
            } catch (RuntimeException error) {
                log.warn("Jev evaluation judge failed; falling back to the existing judge: {}", error.getMessage());
                agentTraceService.recordEvent(
                        "JEV_EVALUATION_FALLBACK",
                        "EVALUATION",
                        judgeInput,
                        Map.of("reason", safeError(error)),
                        elapsedMs(startedAt)
                );
            }
        }
        try {
            ReActAgent agent = agentBuilder.build();
            agent.getMemory().clear();
            Msg response = agentTraceService.callAgent(
                    sessionId,
                    "EvaluationJudgeAgent",
                    modelName,
                    agent,
                    buildUserPrompt(traceId, judgeInput)
            );
            JsonNode root = llmJsonService.parseObject(response.getTextContent());
            return new EvaluationJudgeResult(
                    clampScore(root.path("explanationQuality").asDouble(0)),
                    clampScore(root.path("naturalness").asDouble(0)),
                    root.path("reason").asText("")
            );
        } catch (Exception error) {
            log.warn("Evaluation Judge failed: traceId={}", traceId, error);
            return null;
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String safeError(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private String buildUserPrompt(String traceId, Map<String, Object> judgeInput) throws Exception {
        return """
                traceId：%s
                trace摘要：
                %s
                请按系统要求输出 JSON。
                """.formatted(traceId, objectMapper.writeValueAsString(judgeInput));
    }

    private double clampScore(double value) {
        if (value < 1.0) {
            return 1.0;
        }
        if (value > 5.0) {
            return 5.0;
        }
        return value;
    }
}
