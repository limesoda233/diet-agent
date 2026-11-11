package com.diet.service.meal.intake;

import com.diet.controller.meal.MealDraftController;
import com.diet.auth.AuthService;
import com.diet.auth.BearerAuthenticationFilter;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MealDraftControllerTest {
    private MockMvc mvc(MealDraftService service, AgentTraceService trace) {
        return MockMvcBuilders.standaloneSetup(new MealDraftController(service, trace))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .addFilters(new BearerAuthenticationFilter(mock(AuthService.class), true))
                .build();
    }

    @Test void modelDietExceptionCannotLeakPrivateTextThroughFailureResponseOrTrace() throws Exception {
        ObjectMapper json = new ObjectMapper(); var mapper = mock(AgentTraceMapper.class);
        var trace = new AgentTraceService(mapper,json);
        String privateDetails = "私人做法和健康信息 private-key-456";
        var service = new MealDraftService((s,u) -> Mono.error(new com.diet.exception.DietException(privateDetails)),
                MealDraftServiceTest.options(),json,new MealIntakeProperties(),trace);
        var mvc = mvc(service,trace);
        var response = mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("text",privateDetails))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).doesNotContain(privateDetails,"private-key-456");
        var row = ArgumentCaptor.forClass(RequestTraceRow.class); verify(mapper).insert(row.capture());
        assertThat(row.getValue().getTraceJson()).doesNotContain(privateDetails,"private-key-456");
    }

    @Test void successAndFailureTracesContainMetadataWithoutPrivateInput() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var mapper = mock(AgentTraceMapper.class);
        var trace = new AgentTraceService(mapper,json);
        String privateText = "宫保鸡丁，中午吃，微辣，鸡肉和花生；私人做法先腌十分钟；我有糖尿病；误贴密钥private-key-123";
        var service = new MealDraftService((s,u) -> u.contains("失败")
                ? Mono.error(new IllegalStateException(privateText)) : Mono.just(MealDraftServiceTest.COMPLETE),
                MealDraftServiceTest.options(),json,new MealIntakeProperties(),trace);
        var mvc = mvc(service,trace);
        mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("text",privateText))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.originalText").value(privateText));
        mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("text",privateText+"失败"))))
                .andExpect(status().isBadRequest());
        var rows = ArgumentCaptor.forClass(RequestTraceRow.class); verify(mapper,times(2)).insert(rows.capture());
        assertThat(rows.getAllValues()).allSatisfy(row -> assertThat(row.getTraceJson())
                .contains("inputChars").doesNotContain("宫保鸡丁","先腌十分钟","糖尿病","private-key-123"));
        var events = json.readTree(rows.getAllValues().get(0).getTraceJson()).path("events");
        var input = json.readTree(events.get(1).path("inputPayload").asText());
        assertThat(input.path("inputChars").intValue()).isEqualTo(privateText.length());
        assertThat(input.has("text")).isFalse();
        assertThat(rows.getAllValues().get(1).getStatus()).isEqualTo("FAILED");
    }

    @Test void routeReturnsDraftAndWritesOnlySyntheticTrace() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        var trace = new AgentTraceService(mapper,json);
        var service = new MealDraftService((s,u) -> Mono.just(MealDraftServiceTest.COMPLETE),
                MealDraftServiceTest.options(),json,new MealIntakeProperties(),trace);
        MockMvc mvc = mvc(service,trace);
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
        var mvc = mvc(service,trace);
        mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content("{\"text\":\"鸡丁\",\"ownerUserId\":99}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/diet/meals/personal/draft").contentType("application/json")
                .content("{\"text\":\"鸡丁\"}")) .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        var row = ArgumentCaptor.forClass(RequestTraceRow.class); verify(mapper,times(2)).insert(row.capture());
        assertThat(row.getAllValues()).allMatch(r -> r.getStatus().equals("FAILED"));
        assertThat(row.getAllValues().get(1).getTraceJson()).contains("MEAL_DRAFT_FAILED");
    }
}
