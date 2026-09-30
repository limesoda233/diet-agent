package com.diet.service.recommend;

import com.diet.service.meal.knowledge.MealKnowledge;

import com.diet.agent.factory.AgentFactory;
import com.diet.enums.SourceMode;
import com.diet.model.*;
import com.diet.service.recommend.RecommendResponseAgentService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendEvidenceTest {
    @Test void sendsOnlyTopThreeEvidenceAndPreservesOrderDespiteFabricatedModelIds() {
        var factory=mock(AgentFactory.class); var trace=mock(AgentTraceService.class);
        var agent=mock(ReActAgent.class,RETURNS_DEEP_STUBS);
        when(factory.get("s")).thenReturn(new AgentFactory.AgentSet(null,null,agent));
        var response=mock(Msg.class);
        when(response.getTextContent()).thenReturn("{\"recommendations\":[{\"mealId\":999,\"reason\":\"编造\"},{\"mealId\":2,\"reason\":\"已记录鸡蛋\"}],\"speechText\":\"候选说明\"}");
        when(trace.callAgent(anyString(),anyString(),anyString(),any(),anyString())).thenReturn(response);
        var knowledge=new MealKnowledge(List.of("鸡蛋"),"记录描述","记录做法","USER_PROVIDED","用户菜单");
        var meals=List.of(3L,2L,1L,4L).stream().map(id->new MealItem(id,SourceMode.PUBLIC,null,"候选"+id,SlotBundle.empty(),0.02,knowledge)).toList();
        var service=new RecommendResponseAgentService(factory,new LlmJsonService(new ObjectMapper()),trace,"fixture");
        var result=service.recommendAndRespond("s","晚餐",SourceMode.PUBLIC,SlotBundle.empty(),meals);
        var prompt=ArgumentCaptor.forClass(String.class);
        verify(trace).callAgent(eq("s"),anyString(),eq("fixture"),eq(agent),prompt.capture());
        assertThat(prompt.getValue()).contains("鸡蛋","记录做法","USER_PROVIDED","用户菜单").doesNotContain("候选4");
        assertThat(result.recommend().recommendations()).extracting(RecommendedMealOption::itemId).containsExactly(3L,2L,1L);
        assertThat(result.recommend().recommendations()).allSatisfy(option->assertThat(option.knowledge()).isEqualTo(knowledge));
        when(trace.callAgent(anyString(),anyString(),anyString(),any(),anyString())).thenThrow(new RuntimeException("offline"));
        assertThat(service.recommendAndRespond("s","晚餐",SourceMode.PUBLIC,SlotBundle.empty(),meals).recommend().recommendations()).hasSize(3);
    }
}
