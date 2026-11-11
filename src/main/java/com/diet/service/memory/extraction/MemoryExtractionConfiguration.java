package com.diet.service.memory.extraction;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import com.diet.service.jev.JevClient;
import com.diet.service.jev.JevProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="diet.memory",name="enabled",havingValue="true",matchIfMissing=true)
public class MemoryExtractionConfiguration {
    @Bean
    MemoryExtractor memoryExtractor(@Qualifier("DietLightChatModel") Model model,ObjectMapper json,
            ObjectProvider<JevClient> clients,ObjectProvider<JevProperties> jevProperties,
            @Value("${diet.memory.extraction-timeout:8s}") String timeoutValue,
            @Value("${diet.memory.jev-enabled:false}") boolean jevEnabled,
            @Value("${diet.memory.jev-confidence-threshold:0.85}") double threshold,
            @Value("${diet.memory.jev-timeout:3s}") String jevTimeout) {
        Duration timeout=DurationStyle.detectAndParse(timeoutValue);
        MemoryExtractor baseline=new DeepSeekMemoryExtractor(statelessCall(model,timeout),json,timeout);
        JevClient client=clients.getIfAvailable();
        JevProperties properties=jevProperties.getIfAvailable();
        if (!jevEnabled || client == null || properties == null || !properties.configured()) return baseline;
        return new JevMemoryExtractor(baseline,client,Map.of(
                "TASTE",Set.of("清淡","微辣","中辣","重辣","辣","甜","酸","咸","麻辣","酸辣"),
                "CUISINE",Set.of("川菜","湘菜","粤菜","鲁菜","苏菜","浙菜","闽菜","徽菜","东北菜","西北菜")),
                threshold,DurationStyle.detectAndParse(jevTimeout));
    }

    private BiFunction<String,String,Mono<String>> statelessCall(Model model,Duration timeout) {
        return (system,user) -> Mono.defer(() -> {
            GenerateOptions options=GenerateOptions.builder().stream(false).temperature(0.0).maxTokens(4096)
                    .executionConfig(ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build()).build();
            List<Msg> messages=List.of(Msg.builder().role(MsgRole.SYSTEM).textContent(system).build(),
                    Msg.builder().role(MsgRole.USER).textContent(user).build());
            return model.stream(messages,List.of(),options).flatMapIterable(ChatResponse::getContent)
                    .ofType(TextBlock.class).map(TextBlock::getText).collect(Collectors.joining());
        });
    }
}
