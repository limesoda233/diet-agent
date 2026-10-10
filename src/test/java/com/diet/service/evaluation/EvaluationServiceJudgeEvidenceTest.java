package com.diet.service.evaluation;

import com.diet.mapper.FeedbackMapper;
import com.diet.model.EvaluationJudgeResult;
import com.diet.model.EvaluationRequest;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationServiceJudgeEvidenceTest {

    @Test
    void sendsOriginalUserMessageAndRankedCandidateEvidenceToJudge() {
        AgentTraceService traceService = mock(AgentTraceService.class);
        FeedbackMapper feedbackMapper = mock(FeedbackMapper.class);
        EvaluationJudgeService judgeService = mock(EvaluationJudgeService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        LocalDateTime start = LocalDateTime.of(2026, 9, 21, 10, 0);
        LocalDateTime end = start.plusHours(1);
        RequestTraceRow row = new RequestTraceRow();
        row.setTraceId("trace-evidence");
        row.setSessionId("session-evidence");
        row.setUserId(1L);
        row.setDurationMs(800L);
        row.setCreatedAt(start.plusMinutes(1));
        row.setTraceJson("""
                {"events":[
                  {"eventType":"REQUEST_RECEIVED","inputPayload":"{\\\"message\\\":\\\"晚饭想吃清淡一点\\\"}"},
                  {"eventType":"INTENT_REVISED","outputPayload":"{\\\"intent\\\":\\\"MEAL_RECOMMENDATION\\\",\\\"slots\\\":{\\\"mealTime\\\":[\\\"晚餐\\\"],\\\"taste\\\":[\\\"清淡\\\"]}}"},
                  {"eventType":"MEAL_RANKED","outputPayload":"{\\\"ranked\\\":[{\\\"id\\\":7,\\\"name\\\":\\\"清蒸鱼\\\",\\\"matchScore\\\":0.91,\\\"knowledge\\\":{\\\"ingredients\\\":[\\\"鱼\\\"]}}]}"},
                  {"eventType":"RESPONSE_READY","outputPayload":"{\\\"speechText\\\":\\\"推荐清蒸鱼，因为口味清淡。\\\",\\\"displayBlocks\\\":[{\\\"id\\\":7}]}"}
                ]}
                """);
        when(traceService.findByTimeRange(anyLong(), any(), any(), anyBoolean(), anyInt())).thenReturn(List.of(row));
        when(feedbackMapper.findBySessions(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(judgeService.judge(any(), any(), any())).thenReturn(new EvaluationJudgeResult(4, 5, "ok"));
        EvaluationService service = new EvaluationService(traceService, feedbackMapper, objectMapper, judgeService);

        service.evaluate(1L, new EvaluationRequest(start, end, true, 10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(judgeService).judge(any(), any(), input.capture());
        assertThat(input.getValue().get("userMessage")).isEqualTo("晚饭想吃清淡一点");
        assertThat(input.getValue().get("rankedCandidates").toString())
                .contains("清蒸鱼")
                .contains("ingredients")
                .contains("0.91");
    }
}
