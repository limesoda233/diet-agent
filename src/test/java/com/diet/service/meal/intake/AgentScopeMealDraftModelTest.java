package com.diet.service.meal.intake;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeMealDraftModelTest {
    @Test
    void asksModelForJsonOutputInsteadOfFreeFormText() {
        Model model = mock(Model.class);
        when(model.stream(anyList(), anyList(), any(GenerateOptions.class))).thenReturn(Flux.empty());

        new AgentScopeMealDraftModel(model, Duration.ofSeconds(10))
                .extract("只输出 JSON", "鸡丁").block();

        var options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model).stream(anyList(), anyList(), options.capture());
        assertThat(options.getValue().getAdditionalBodyParams())
                .containsEntry("response_format", Map.of("type", "json_object"));
    }
}
