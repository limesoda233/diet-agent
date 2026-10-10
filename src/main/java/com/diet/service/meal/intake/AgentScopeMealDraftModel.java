package com.diet.service.meal.intake;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/** A single stateless light model call; the service owns the outer end-to-end timeout. */
public final class AgentScopeMealDraftModel implements MealDraftModel {
    private final Model model;
    private final Duration timeout;
    public AgentScopeMealDraftModel(Model model,Duration timeout) { this.model=model; this.timeout=timeout; }
    @Override public Mono<String> extract(String systemPrompt,String userPrompt) {
        return Mono.defer(() -> {
            GenerateOptions options = GenerateOptions.builder().stream(false).temperature(0.0).maxTokens(4096)
                    .executionConfig(ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build()).build();
            List<Msg> messages = List.of(Msg.builder().role(MsgRole.SYSTEM).textContent(systemPrompt).build(),
                    Msg.builder().role(MsgRole.USER).textContent(userPrompt).build());
            return model.stream(messages,List.of(),options).flatMapIterable(ChatResponse::getContent)
                    .ofType(TextBlock.class).map(TextBlock::getText).collect(Collectors.joining());
        });
    }
}
