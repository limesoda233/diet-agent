package com.diet.service.intent;

import com.diet.agent.factory.AgentFactory;
import com.diet.enums.Intent;
import com.diet.model.IntentResult;
import com.diet.model.SlotBundle;
import com.diet.service.jev.JevIntentRecognitionService;
import com.diet.service.slot.SlotOptionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentAgentServiceJevTest {

    @Test
    void usesAcceptedJevDecisionWithoutCallingGenerativeAgent() {
        AgentFactory agentFactory = mock(AgentFactory.class);
        LlmJsonService llmJsonService = mock(LlmJsonService.class);
        SlotOptionService slotOptionService = mock(SlotOptionService.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        JevIntentRecognitionService jev = mock(JevIntentRecognitionService.class);
        Map<String, List<String>> options = Map.of("mealTime", List.of("晚餐"));
        IntentResult expected = new IntentResult(
                Intent.MEAL_RECOMMENDATION,
                new SlotBundle(List.of("晚餐"), null, null, null, null, null, null),
                0.90
        );
        when(slotOptionService.findAllOptions()).thenReturn(options);
        when(jev.available()).thenReturn(true);
        when(jev.recognize("晚饭吃什么", SlotBundle.empty(), List.of(), options)).thenReturn(expected);
        when(jev.isAccepted(expected)).thenReturn(true);
        IntentAgentService service = new IntentAgentService(
                agentFactory, llmJsonService, slotOptionService, traceService, jev, "deepseek-chat"
        );

        IntentResult result = service.recognize("session-1", 1L, "晚饭吃什么", SlotBundle.empty(), List.of());

        assertThat(result).isSameAs(expected);
        verify(agentFactory, never()).get(anyString());
    }

    @Test
    void fallsBackToExistingAgentWhenJevConfidenceIsBelowThreshold() throws Exception {
        AgentFactory agentFactory = mock(AgentFactory.class);
        LlmJsonService llmJsonService = mock(LlmJsonService.class);
        SlotOptionService slotOptionService = mock(SlotOptionService.class);
        AgentTraceService traceService = mock(AgentTraceService.class);
        JevIntentRecognitionService jev = mock(JevIntentRecognitionService.class);
        ReActAgent agent = mock(ReActAgent.class);
        Map<String, List<String>> options = Map.of();
        IntentResult uncertain = new IntentResult(Intent.OTHER, SlotBundle.empty(), 0.40);
        when(slotOptionService.findAllOptions()).thenReturn(options);
        when(jev.available()).thenReturn(true);
        when(jev.recognize("早餐和午餐都推荐", SlotBundle.empty(), List.of(), options)).thenReturn(uncertain);
        when(jev.isAccepted(uncertain)).thenReturn(false);
        when(agentFactory.get("session-2")).thenReturn(new AgentFactory.AgentSet(agent, null, null));
        when(agent.getMemory()).thenReturn(new InMemoryMemory());
        Msg response = Msg.builder().role(MsgRole.ASSISTANT)
                .textContent("{\"intent\":\"MEAL_PLAN\",\"slots\":{},\"confidence\":0.88}")
                .build();
        when(traceService.callAgent(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(response);
        when(llmJsonService.parseObject(anyString())).thenReturn(new ObjectMapper().readTree(response.getTextContent()));
        IntentAgentService service = new IntentAgentService(
                agentFactory, llmJsonService, slotOptionService, traceService, jev, "deepseek-chat"
        );

        IntentResult result = service.recognize("session-2", 1L, "早餐和午餐都推荐", SlotBundle.empty(), List.of());

        assertThat(result.intent()).isEqualTo(Intent.MEAL_PLAN);
        assertThat(result.confidence()).isEqualTo(0.88);
    }
}
