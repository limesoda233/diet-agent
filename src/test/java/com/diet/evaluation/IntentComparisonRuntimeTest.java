package com.diet.evaluation;

import com.diet.enums.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class IntentComparisonRuntimeTest {
    @TempDir Path directory;

    @Test
    void preservesLowScoreJevPredictionBeforeActualProductionFallbackAndChargesBothCalls() throws Exception {
        try (var fixture = new Fixture()) {
            var runtime = new IntentComparisonRuntime(config(fixture));
            var rows = runtime.evaluate(example(), "main", 1, true);
            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).finalIntent()).isEqualTo(Intent.RECIPE_QUERY);
            assertThat(rows.get(1).predictedIntent()).isEqualTo(Intent.OTHER);
            assertThat(rows.get(1).confidence()).isEqualTo(0.3);
            assertThat(rows.get(1).fallbackReason()).isEqualTo("low_confidence");
            assertThat(rows.get(2).finalIntent()).isEqualTo(Intent.RECIPE_QUERY);
            assertThat(rows.get(2).source()).isEqualTo("DEEPSEEK");
            assertThat(rows.get(2).modelCalls()).isEqualTo(2);
            assertThat(rows.get(2).inputTokens()).isEqualTo(110);
            assertThat(rows.get(2).outputTokens()).isEqualTo(14);
            // Same session, input, known slots and explicit history reach both generative calls.
            assertThat(fixture.agentRequests).hasSize(2);
            assertThat(fixture.agentRequests.get(0)).isEqualTo(fixture.agentRequests.get(1));
            assertThat(fixture.jevRequest).contains("冰箱里剩了点鸡腿肉", "currentMessage", "knownSlots", "recentHistory");
        }
    }

    @Test
    void authenticationFailureRemainsAnErrorEvenWhenFallbackGetsCorrectAnswer() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jevStatus = 401;
            var rows = new IntentComparisonRuntime(config(fixture)).evaluate(example(), "main", 1, false);
            assertThat(rows.get(1).status()).isEqualTo("ERROR");
            assertThat(rows.get(1).predictedIntent()).isNull();
            assertThat(rows.get(1).finalIntent()).isNull();
            assertThat(rows.get(1).fallbackReason()).isEqualTo("jev_http_401");
            assertThat(rows.get(2).finalIntent()).isEqualTo(Intent.RECIPE_QUERY);
            assertThat(rows.get(2).inputTokens()).isNull();
        }
    }

    @Test
    void failedProviderRequestRemainsVisibleWithUnknownUsage() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.agentFailuresRemaining.set(1);
            var rows = new IntentComparisonRuntime(config(fixture)).evaluate(example(), "main", 1, true);
            assertThat(rows.get(0).status()).isEqualTo("RULE_FALLBACK");
            assertThat(rows.get(0).predictedIntent()).isNull();
            assertThat(rows.get(0).modelCalls()).isEqualTo(1);
            assertThat(rows.get(0).inputTokens()).isNull();
            assertThat(rows.get(0).outputTokens()).isNull();
            assertThat(fixture.agentRequests).hasSize(2);
        }
    }

    @Test
    void acceptedJevDecisionDoesNotConsumeFallbackModelRequest() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.jevConfidence = 0.95;
            var rows = new IntentComparisonRuntime(config(fixture)).evaluate(example(), "main", 1, true);
            assertThat(rows.get(2).source()).isEqualTo("JEV");
            assertThat(rows.get(2).modelCalls()).isEqualTo(1);
            assertThat(rows.get(2).predictedIntent()).isEqualTo(Intent.OTHER);
            assertThat(rows.get(2).fallbackReason()).isNull();
            assertThat(fixture.agentRequests).hasSize(1);
        }
    }

    @Test
    void malformedGenerativeAnswerIsVisibleAsRuleFallbackInsteadOfModelPrediction() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.agentContent = "not structured JSON";
            var rows = new IntentComparisonRuntime(config(fixture)).evaluate(example(), "main", 1, true);
            assertThat(rows.get(0).source()).isEqualTo("RULE_FALLBACK");
            assertThat(rows.get(0).status()).isEqualTo("RULE_FALLBACK");
            assertThat(rows.get(0).predictedIntent()).isNull();
            assertThat(rows.get(0).confidence()).isNull();
        }
    }

    @Test
    void partialGenerativeUsageIsNotPresentedAsCompleteConsumption() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.completeAgentUsage = false;
            var rows = new IntentComparisonRuntime(config(fixture)).evaluate(example(), "main", 1, true);
            assertThat(rows.get(0).status()).isEqualTo("OK");
            assertThat(rows.get(0).inputTokens()).isNull();
            assertThat(rows.get(0).outputTokens()).isNull();
        }
    }

    private LocalIntentEvaluationConfig config(Fixture f) throws Exception {
        return LocalIntentEvaluationConfig.load(directory.resolve("absent.env"), Map.of(
                "DEEPSEEK_API_KEY", "fixture-only", "JEV_API_KEY", "fixture-only",
                "DEEPSEEK_BASE_URL", f.url(), "JEV_ENDPOINT", f.url() + "/v1/systemone"));
    }
    private static IntentEvaluationDataset.Case example() {
        return new IntentEvaluationDataset.Case("fixture", "ingredient_reverse", "冰箱里剩了点鸡腿肉，可以做什么",
                List.of(), false, Intent.RECIPE_QUERY, Intent.RECIPE_QUERY);
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final List<String> agentRequests = new CopyOnWriteArrayList<>();
        final ObjectMapper json = new ObjectMapper();
        volatile int jevStatus = 200;
        volatile double jevConfidence = 0.3;
        volatile String agentContent = "{\"intent\":\"RECIPE_QUERY\",\"slots\":{},\"confidence\":0.9}";
        final AtomicInteger agentFailuresRemaining = new AtomicInteger();
        volatile boolean completeAgentUsage = true;
        volatile String jevRequest;
        Fixture() throws Exception {
            server.createContext("/", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean jev = exchange.getRequestURI().getPath().endsWith("systemone");
                String body;
                if (jev) {
                    jevRequest = request;
                    body = "{\"answers\":{\"intent\":{\"type\":\"choice\",\"choice\":\"OTHER\",\"confidence\":" + jevConfidence + "}},\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}";
                } else {
                    agentRequests.add(request);
                    if (agentFailuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        byte[] retry = "{\"error\":{\"message\":\"fixture retry\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(503, retry.length); exchange.getResponseBody().write(retry); exchange.close(); return;
                    }
                    String content = agentContent;
                    Map<String, Object> usage = completeAgentUsage
                            ? Map.of("prompt_tokens", 100, "completion_tokens", 12, "total_tokens", 112)
                            : Map.of("prompt_tokens", 100);
                    if (json.readTree(request).path("stream").asBoolean(false)) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                        body = "data: " + json.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                                "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", content)))))
                                + "\n\ndata: " + json.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                                "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")), "usage", usage))
                                + "\n\ndata: [DONE]\n\n";
                    } else {
                        body = json.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion", "model", "deepseek-chat",
                                "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content),
                                        "finish_reason", "stop")), "usage", usage));
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(jev ? jevStatus : 200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
