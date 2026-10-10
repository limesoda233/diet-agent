package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.trace.AgentTraceService;
import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
class AgentScopeRecipeQueryRewriterTest {

    @Test
    void parsesJsonRewriteAndKeepsServerSideConstraints() {
        Model model = modelReturning("```json\n{\"query\":\"酸辣土豆丝 做法\"}\n```");
        RecipeQuery original = new RecipeQuery(
                "马铃薯丝怎么炒得酸辣", List.of("土豆"), List.of("香菜"), List.of("炒锅"), 15);

        RecipeQuery actual = new AgentScopeRecipeQueryRewriter(
                model, new ObjectMapper(), Duration.ofSeconds(1), null).rewrite(original).block();

        assertThat(actual.text()).isEqualTo("酸辣土豆丝 做法");
        assertThat(actual.requiredIngredients()).containsExactly("土豆");
        assertThat(actual.excludedIngredients()).containsExactly("香菜");
        assertThat(actual.requiredTools()).containsExactly("炒锅");
        assertThat(actual.maxMinutes()).isEqualTo(15);
    }

    @Test
    void fallsBackToSameOriginalForInvalidJsonEmptyResponseAndModelFailure() {
        RecipeQuery original = new RecipeQuery("西红市炒鸡蛋咋做", List.of(), List.of(), List.of(), null);
        Model invalid = modelReturning("西红柿炒鸡蛋");
        Model empty = modelReturning("");
        Model failing = new StubModel(Flux.error(new IllegalStateException("offline")));

        assertThat(rewriter(invalid).rewrite(original).block()).isSameAs(original);
        assertThat(rewriter(empty).rewrite(original).block()).isSameAs(original);
        assertThat(rewriter(failing).rewrite(original).block()).isSameAs(original);
    }

    @Test
    void rejectsUnknownFieldsSoModelCannotSmuggleStructuredConstraints() {
        RecipeQuery original = new RecipeQuery("鸡翅怎么做", List.of(), List.of(), List.of(), null);
        Model model = modelReturning("{\"query\":\"可乐鸡翅\",\"excludedIngredients\":[\"辣椒\"]}");

        assertThat(rewriter(model).rewrite(original).block()).isSameAs(original);
    }

    @Test
    void traceFailureDoesNotBreakSuccessfulRewrite() {
        AgentTraceService brokenTrace = new AgentTraceService(null, null) {
            @Override
            public <T> Supplier<T> propagate(Supplier<T> work) {
                return () -> { throw new IllegalStateException("trace storage unavailable"); };
            }
        };
        RecipeQuery original = new RecipeQuery("马铃薯丝咋炒", List.of(), List.of(), List.of(), null);

        RecipeQuery actual = new AgentScopeRecipeQueryRewriter(
                modelReturning("{\"query\":\"酸辣土豆丝 做法\"}"),
                new ObjectMapper(), Duration.ofSeconds(1), brokenTrace).rewrite(original).block();

        assertThat(actual.text()).isEqualTo("酸辣土豆丝 做法");
    }

    @Test
    void rejectsDuplicateFieldsAndReportsFallbackSeparatelyFromUnchanged() {
        RecipeQuery original = new RecipeQuery("鸡翅怎么做", List.of(), List.of(), List.of(), null);
        var duplicate = rewriter(modelReturning("{\"query\":\"a\",\"query\":\"b\"}"))
                .rewriteDetailed(original).block();
        var unchanged = rewriter(modelReturning("{\"query\":\"鸡翅怎么做\"}"))
                .rewriteDetailed(original).block();

        assertThat(duplicate.status()).isEqualTo("FALLBACK");
        assertThat(duplicate.errorType()).isEqualTo("JsonParseException");
        assertThat(duplicate.query()).isSameAs(original);
        assertThat(unchanged.status()).isEqualTo("UNCHANGED");
        assertThat(unchanged.errorType()).isNull();
    }

    @Test
    void timeoutFallsBackAndCrossThreadTraceRecordsOneTerminalEvent() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceService trace = new AgentTraceService(mapper, json);
        RecipeQuery original = new RecipeQuery("鸡翅怎么做", List.of(), List.of(), List.of(), null);
        var rewriter = new AgentScopeRecipeQueryRewriter(
                new StubModel(Flux.never()), json, Duration.ofMillis(30), trace);

        try (var scope = trace.openTrace("rewrite-timeout", "s", 1L)) {
            var outcome = rewriter.rewriteDetailed(original).block(Duration.ofSeconds(2));
            assertThat(outcome.status()).isEqualTo("FALLBACK");
            assertThat(outcome.errorType()).isEqualTo("TimeoutException");
            assertThat(outcome.query()).isSameAs(original);
        }
        ArgumentCaptor<RequestTraceRow> row = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper).insert(row.capture());
        var events = json.readTree(row.getValue().getTraceJson()).path("events");
        assertThat(events.size()).isEqualTo(1);
        assertThat(events.get(0).path("eventType").asText()).isEqualTo("RECIPE_QUERY_REWRITE");
        var output = json.readTree(events.get(0).path("outputPayload").asText());
        assertThat(output.path("status").asText()).isEqualTo("FALLBACK");
        assertThat(output.path("effectiveQuery").asText()).isEqualTo(original.text());
        assertThat(row.getValue().getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void successfulAsyncResponseKeepsRequestTraceScope() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceService trace = new AgentTraceService(mapper, json);
        RecipeQuery original = new RecipeQuery("马铃薯丝咋炒", List.of(), List.of(), List.of(), null);
        var response = ChatResponse.builder().content(List.of(TextBlock.builder()
                .text("{\"query\":\"酸辣土豆丝 做法\"}").build())).build();
        try (var scope = trace.openTrace("rewrite-success", "s", 1L)) {
            var outcome = new AgentScopeRecipeQueryRewriter(
                    new StubModel(Flux.just(response).subscribeOn(Schedulers.boundedElastic())),
                    json, Duration.ofSeconds(1), trace).rewriteDetailed(original).block();
            assertThat(outcome.status()).isEqualTo("REWRITTEN");
        }
        ArgumentCaptor<RequestTraceRow> row = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper).insert(row.capture());
        var events = json.readTree(row.getValue().getTraceJson()).path("events");
        assertThat(events.size()).isEqualTo(1);
        var output = json.readTree(events.get(0).path("outputPayload").asText());
        assertThat(output.path("status").asText()).isEqualTo("REWRITTEN");
        assertThat(output.path("effectiveQuery").asText()).isEqualTo("酸辣土豆丝 做法");
    }

    private AgentScopeRecipeQueryRewriter rewriter(Model model) {
        return new AgentScopeRecipeQueryRewriter(model, new ObjectMapper(), Duration.ofSeconds(1), null);
    }

    private Model modelReturning(String text) {
        ChatResponse response = ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
        return new StubModel(Flux.just(response));
    }

    private record StubModel(Flux<ChatResponse> responses) implements Model {
        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return responses;
        }

        @Override
        public String getModelName() {
            return "test-light-model";
        }
    }
}
