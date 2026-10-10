package com.diet.service.evaluation;

import com.diet.agent.builder.EvaluationJudgeAgentBuilder;
import com.diet.model.EvaluationJudgeResult;
import com.diet.service.jev.JevEvaluationJudge;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationJudgeServiceJevTest {

    @Test
    void prefersJevJudgeWhenConfigured() {
        EvaluationJudgeAgentBuilder builder = mock(EvaluationJudgeAgentBuilder.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        LlmJsonService llmJsonService = mock(LlmJsonService.class);
        JevEvaluationJudge jev = mock(JevEvaluationJudge.class);
        Map<String, Object> input = Map.of("finalReply", "推荐清蒸鱼，因为更清淡。", "userMessage", "想吃清淡的");
        EvaluationJudgeResult expected = new EvaluationJudgeResult(4.5, 5.0, "jev structured scores");
        when(jev.available()).thenReturn(true);
        when(jev.judge(input)).thenReturn(expected);
        EvaluationJudgeService service = new EvaluationJudgeService(
                builder, traceService, llmJsonService, new ObjectMapper(), jev, "deepseek-chat"
        );

        EvaluationJudgeResult result = service.judge("trace-1", "session-1", input);

        assertThat(result).isEqualTo(expected);
        verify(builder, never()).build();
    }

    @Test
    void fallsBackToExistingJudgeWhenJevCallFails() throws Exception {
        EvaluationJudgeAgentBuilder builder = mock(EvaluationJudgeAgentBuilder.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        LlmJsonService llmJsonService = mock(LlmJsonService.class);
        JevEvaluationJudge jev = mock(JevEvaluationJudge.class);
        ReActAgent agent = mock(ReActAgent.class);
        Map<String, Object> input = Map.of("finalReply", "推荐清蒸鱼。", "userMessage", "想吃清淡的");
        when(jev.available()).thenReturn(true);
        when(jev.judge(input)).thenThrow(new IllegalStateException("temporary failure"));
        when(builder.build()).thenReturn(agent);
        when(agent.getMemory()).thenReturn(new InMemoryMemory());
        Msg response = Msg.builder().role(MsgRole.ASSISTANT)
                .textContent("{\"explanationQuality\":4,\"naturalness\":5,\"reason\":\"fallback\"}")
                .build();
        when(traceService.callAgent(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(response);
        when(llmJsonService.parseObject(anyString())).thenReturn(new ObjectMapper().readTree(response.getTextContent()));
        EvaluationJudgeService service = new EvaluationJudgeService(
                builder, traceService, llmJsonService, new ObjectMapper(), jev, "deepseek-chat"
        );

        EvaluationJudgeResult result = service.judge("trace-2", "session-2", input);

        assertThat(result.explanationQuality()).isEqualTo(4.0);
        assertThat(result.naturalness()).isEqualTo(5.0);
        assertThat(result.reason()).isEqualTo("fallback");
    }
}
