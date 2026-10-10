package com.diet.service.meal.intake;

import com.diet.controller.meal.MealDraftController;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MealDraftControllerTest {
    @Test void routeReturnsDraftAndWritesOnlySyntheticTrace() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        var trace = new AgentTraceService(mapper,json);
        var service = new MealDraftService((s,u) -> Mono.just(MealDraftServiceTest.COMPLETE),
                MealDraftServiceTest.options(),json,new MealIntakeProperties(),trace);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MealDraftController(service,trace)).build();
        mvc.perform(post("/api/v1/diet/meals/personal/draft").header("X-User-Id","7")
                .contentType("application/json").content("{\"text\":\"宫保鸡丁，中午吃，微辣，鸡肉和花生\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.data.mealTime[0]").value("午餐"))
                .andExpect(jsonPath("$.savedMealId").isEmpty());
        var row = ArgumentCaptor.forClass(RequestTraceRow.class); verify(mapper).insert(row.capture());
        assertThat(row.getValue().getSessionId()).startsWith("meal_draft_");
        assertThat(row.getValue().getUserId()).isEqualTo(7L);
        assertThat(row.getValue().getTraceJson()).contains("MEAL_DRAFT_GENERATED").doesNotContain("MEAL_SAVED");
    }

    @Test void rejectsControlFieldsAndRecordsFailedGeneration() throws Exception {
        ObjectMapper json = new ObjectMapper(); var mapper = mock(AgentTraceMapper.class);
        var trace = new AgentTraceService(mapper,json);
        var service = new MealDraftService((s,u) -> Mono.just("{}"),MealDraftServiceTest.options(),json,new MealIntakeProperties(),trace);
        var mvc = MockMvcBuilders.standaloneSetup(new MealDraftController(service,trace)).build();
        mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content("{\"text\":\"鸡丁\",\"ownerUserId\":99}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content("{\"text\":\"鸡丁\"}")) .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        var row = ArgumentCaptor.forClass(RequestTraceRow.class); verify(mapper,times(2)).insert(row.capture());
        assertThat(row.getAllValues()).allMatch(r -> r.getStatus().equals("FAILED"));
        assertThat(row.getAllValues().get(1).getTraceJson()).contains("MEAL_DRAFT_FAILED");
    }
}
