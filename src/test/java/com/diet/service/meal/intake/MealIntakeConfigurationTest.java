package com.diet.service.meal.intake;

import com.diet.service.slot.SlotOptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MealIntakeConfigurationTest {
    @Test void wiredServiceUsesLightModelWithStatelessMessagesAndSingleAttempt() {
        var light = new RecordingModel();
        new ApplicationContextRunner().withUserConfiguration(MealIntakeConfiguration.class)
                .withBean("DietLightChatModel",Model.class,() -> light)
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withBean(SlotOptionService.class,MealDraftServiceTest::options)
                .withPropertyValues("diet.meal-intake.timeout=1s","diet.meal-intake.max-input-chars=100")
                .run(context -> {
                    assertThat(context).hasSingleBean(MealDraftService.class);
                    var service = context.getBean(MealDraftService.class);
                    assertThat(service.generate("鸡丁").data().name()).isEqualTo("鸡丁");
                    assertThat(service.generate("鸡丁").data().name()).isEqualTo("鸡丁");
                    assertThat(light.requests).hasSize(2);
                    for (var request : light.requests) {
                        assertThat(request.messages()).hasSize(2);
                        assertThat(request.messages().get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
                        assertThat(request.messages().get(1).getRole()).isEqualTo(MsgRole.USER);
                        assertThat(request.tools()).isEmpty();
                        assertThat(request.options().getStream()).isFalse();
                        assertThat(request.options().getExecutionConfig().getMaxAttempts()).isEqualTo(1);
                        assertThat(request.options().getExecutionConfig().getTimeout()).isEqualTo(Duration.ofSeconds(1));
                        assertThat(request.messages().get(0).getTextContent()).contains("午餐","微辣");
                    }
                    assertThatThrownBy(() -> service.generate("啊".repeat(101))).isInstanceOf(com.diet.exception.DietException.class);
                });
    }

    private record Request(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) { }
    private static final class RecordingModel implements Model {
        private final List<Request> requests = new ArrayList<>();
        @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
            requests.add(new Request(messages,tools,options));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder()
                    .text("{\"name\":{\"value\":\"鸡丁\",\"evidence\":\"鸡丁\"}}").build())).build());
        }
        @Override public String getModelName() { return "test-light"; }
    }
}
