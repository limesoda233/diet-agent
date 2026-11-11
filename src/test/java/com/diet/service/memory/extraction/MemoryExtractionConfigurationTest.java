package com.diet.service.memory.extraction;

import com.diet.service.jev.JevClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MemoryExtractionConfigurationTest {
    @Test void defaultBeanUsesFreshStatelessLightModelRequestsAndDoesNotConnectJev() {
        var light=new RecordingModel();
        runner(light).withBean(JevClient.class,() -> (s,q) -> { throw new AssertionError("Jev default must remain off"); })
                .withPropertyValues("diet.memory.extraction-timeout=1s")
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryExtractor.class);
                    MemoryExtractor extractor=context.getBean(MemoryExtractor.class);
                    assertThat(extractor.extract("我喜欢辣")).hasSize(1);
                    assertThat(extractor.extract("我喜欢辣")).hasSize(1);
                    assertThat(light.requests).hasSize(2).allSatisfy(request -> {
                        assertThat(request.messages()).hasSize(2);
                        assertThat(request.messages().get(0).getRole()).isEqualTo(MsgRole.SYSTEM);
                        assertThat(request.messages().get(1).getRole()).isEqualTo(MsgRole.USER);
                        assertThat(request.tools()).isEmpty();
                        assertThat(request.options().getStream()).isFalse();
                        assertThat(request.options().getExecutionConfig().getMaxAttempts()).isEqualTo(1);
                        assertThat(request.options().getExecutionConfig().getTimeout()).isEqualTo(Duration.ofSeconds(1));
                    });
                });
    }
    @Test void disabledMemoryDoesNotRequireAModelOrCreateAnExtractor() {
        new ApplicationContextRunner().withUserConfiguration(MemoryExtractionConfiguration.class)
                .withPropertyValues("diet.memory.enabled=false").run(context -> assertThat(context).doesNotHaveBean(MemoryExtractor.class));
    }
    @Test void unavailableOptionalJevStillUsesDeepSeekBaseline() {
        runner(new RecordingModel()).withPropertyValues("diet.memory.jev-enabled=true")
                .run(context -> assertThat(context.getBean(MemoryExtractor.class).extract("我喜欢辣")).hasSize(1));
    }
    private ApplicationContextRunner runner(Model model) {
        return new ApplicationContextRunner().withUserConfiguration(MemoryExtractionConfiguration.class)
                .withBean("DietLightChatModel",Model.class,() -> model).withBean(ObjectMapper.class,ObjectMapper::new);
    }
    private record Request(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) { }
    private static class RecordingModel implements Model {
        private final List<Request> requests=new ArrayList<>();
        @Override public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
            requests.add(new Request(messages,tools,options));
            return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text("""
                    [{"type":"TASTE","value":"辣","subject":"SELF","stance":"LIKE","scope":"LONG_TERM","operation":"ADD","evidence":"我喜欢辣"}]
                    """).build())).build());
        }
        @Override public String getModelName() { return "test-memory-model"; }
    }
}
