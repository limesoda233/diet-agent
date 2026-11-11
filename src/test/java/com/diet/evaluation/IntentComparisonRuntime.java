package com.diet.evaluation;

import com.diet.agent.builder.ClarifyAgentBuilder;
import com.diet.agent.builder.IntentAgentBuilder;
import com.diet.agent.builder.RecommendResponseAgentBuilder;
import com.diet.agent.factory.AgentFactory;
import com.diet.agent.loader.PromptLoader;
import com.diet.enums.Intent;
import com.diet.enums.SourceMode;
import com.diet.mapper.SlotOptionMapper;
import com.diet.model.IntentResult;
import com.diet.model.SessionState;
import com.diet.model.SlotBundle;
import com.diet.service.intent.IntentAgentService;
import com.diet.service.intent.IntentReviseService;
import com.diet.service.jev.HttpJevClient;
import com.diet.service.jev.JevClient;
import com.diet.service.jev.JevProperties;
import com.diet.service.jev.JevIntentRecognitionService;
import com.diet.service.jev.JevResponse;
import com.diet.service.slot.SlotOptionService;
import com.diet.service.trace.AgentTraceService;
import com.diet.util.LlmJsonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.transport.HttpTransportFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Calls production recognition/revision code, but never opens a DB Trace or runs recommendation/RAG. */
final class IntentComparisonRuntime {
    private final IntentAgentService baseline;
    private final IntentAgentService chain;
    private final IntentReviseService revise = new IntentReviseService();
    private final RecordingTrace trace;
    private final RecordingClient client;
    private final ObservedIntentHttpTransport transport;
    final String deepSeekModel;
    final String jevModel;
    final double threshold;
    final double slotThreshold;
    final Duration jevTimeout;
    final Duration agentTimeout;

    IntentComparisonRuntime(LocalIntentEvaluationConfig config) {
        this(config, true);
    }

    IntentComparisonRuntime(LocalIntentEvaluationConfig config, boolean requireJev) {
        requireKey(config, "DEEPSEEK_API_KEY");
        if (requireJev) requireKey(config, "JEV_API_KEY");
        deepSeekModel = config.value("DEEPSEEK_LIGHT_MODEL", "deepseek-chat");
        jevModel = config.value("JEV_MODEL", "jev-latest");
        threshold = probability(config.value("JEV_INTENT_CONFIDENCE_THRESHOLD", "0.70"));
        slotThreshold = probability(config.value("JEV_SLOT_PROBABILITY_THRESHOLD", "0.70"));
        jevTimeout = org.springframework.boot.convert.DurationStyle.detectAndParse(config.value("JEV_TIMEOUT", "3s"));
        agentTimeout = Duration.ofSeconds(Long.parseLong(System.getProperty("intent.compare.agent-timeout-seconds", "30")));
        if (jevTimeout.isNegative() || jevTimeout.isZero() || agentTimeout.isNegative() || agentTimeout.isZero())
            throw new IllegalArgumentException("Evaluation timeouts must be positive");
        transport = new ObservedIntentHttpTransport(HttpTransportFactory.getDefault());
        var model = OpenAIChatModel.builder().apiKey(config.value("DEEPSEEK_API_KEY", ""))
                .httpTransport(transport)
                .baseUrl(config.value("DEEPSEEK_BASE_URL", "https://api.deepseek.com")).modelName(deepSeekModel).build();
        PromptLoader prompts = new PromptLoader();
        var factory = new AgentFactory(new IntentAgentBuilder(model, prompts), new ClarifyAgentBuilder(model, prompts),
                new RecommendResponseAgentBuilder(model, prompts), "intent-comparison-frozen");
        SlotOptionMapper slotMapper = mock(SlotOptionMapper.class);
        when(slotMapper.findEnabledValues(anyString())).thenAnswer(invocation ->
                IntentEvaluationDataset.slotOptions().getOrDefault(invocation.getArgument(0), List.of()));
        var slotService = new SlotOptionService(slotMapper);
        ObjectMapper json = new ObjectMapper();
        trace = new RecordingTrace(json, agentTimeout, transport);
        JevProperties properties = new JevProperties();
        properties.setApiKey(config.value("JEV_API_KEY", "")); properties.setModel(jevModel);
        properties.setEndpoint(config.value("JEV_ENDPOINT", "https://api.typesafe.ai/v1/systemone"));
        properties.setTimeout(jevTimeout); properties.setIntentConfidenceThreshold(threshold);
        properties.setSlotProbabilityThreshold(slotThreshold);
        client = new RecordingClient(new HttpJevClient(properties,
                HttpClient.newBuilder().connectTimeout(jevTimeout).build()));
        JevProperties disabled = new JevProperties(); disabled.setEnabled(false);
        baseline = new IntentAgentService(factory, new LlmJsonService(json), slotService, trace,
                new JevIntentRecognitionService(client, disabled), deepSeekModel);
        chain = new IntentAgentService(factory, new LlmJsonService(json), slotService, trace,
                new JevIntentRecognitionService(client, properties), deepSeekModel);
    }

    List<IntentComparisonReport.Row> evaluate(IntentEvaluationDataset.Case example, String dataset,
                                             int repeat, boolean baselineFirst) {
        if (baselineFirst) {
            var a = call(example, dataset, repeat, false);
            var c = call(example, dataset, repeat, true);
            return List.of(a.get(0), c.get(0), c.get(1));
        }
        var c = call(example, dataset, repeat, true);
        var a = call(example, dataset, repeat, false);
        return List.of(a.get(0), c.get(0), c.get(1));
    }

    List<IntentComparisonReport.Row> evaluateBaseline(IntentEvaluationDataset.Case example, String dataset, int repeat) {
        return call(example, dataset, repeat, false);
    }

    private List<IntentComparisonReport.Row> call(IntentEvaluationDataset.Case example, String dataset,
                                                 int repeat, boolean withJev) {
        trace.clear(); client.clear(); transport.reset();
        String session = "intent-compare-" + dataset + "-" + repeat + "-" + example.id();
        SessionState state = SessionState.fresh(session, 1L, SourceMode.PUBLIC)
                .withLastRecommendations(example.hasPreviousRecommendations() ? List.of(9001L) : List.of());
        long started = System.nanoTime();
        IntentResult result = (withJev ? chain : baseline).recognize(session, 1L, example.utterance(),
                SlotBundle.empty(), example.conversationHistory());
        IntentResult finalResult = revise.revise(state, result, example.utterance());
        long latency = elapsed(started);
        boolean fromAgent = trace.agentCalls > 0;
        boolean validAgent = fromAgent && trace.validAgentResult;
        boolean validJev = !fromAgent && trace.jevResult != null;
        String source = validJev ? "JEV" : validAgent ? "DEEPSEEK" : "RULE_FALLBACK";
        int calls = transport.attempts() + client.calls;
        Long input = totalUsage(trace.inputTokens, client.inputTokens, trace.agentCalls, client.calls);
        Long output = totalUsage(trace.outputTokens, client.outputTokens, trace.agentCalls, client.calls);
        var combined = row(example, dataset, repeat, withJev ? "JEV_CHAIN" : "BASELINE",
                validAgent || validJev ? result.intent() : null, finalResult.intent(),
                validAgent || validJev ? result.confidence() : null,
                validAgent || validJev ? "OK" : "RULE_FALLBACK", source, trace.fallbackReason,
                latency, calls, input, output);
        if (!withJev) return List.of(combined);
        // The raw decision is captured BEFORE confidence acceptance and fallback. Low scores stay visible.
        IntentResult raw = trace.jevResult;
        IntentResult revisedRaw = raw == null ? null : revise.revise(state, raw, example.utterance());
        var jevRaw = row(example, dataset, repeat, "JEV_RAW", raw == null ? null : raw.intent(),
                revisedRaw == null ? null : revisedRaw.intent(), raw == null ? null : raw.confidence(),
                raw == null ? "ERROR" : "OK", raw == null ? "NO_PREDICTION" : "JEV", trace.fallbackReason,
                client.latencyMs, client.calls, client.inputTokens, client.outputTokens);
        return List.of(jevRaw, combined);
    }

    private static IntentComparisonReport.Row row(IntentEvaluationDataset.Case c, String dataset, int repeat,
            String arm, Intent raw, Intent revised, Double confidence, String status, String source,
            String reason, long latency, int calls, Long input, Long output) {
        return new IntentComparisonReport.Row(c.id(), dataset, arm, repeat, c.slice(), c.utterance(),
                c.expectedAgentIntent(), c.expectedFinalIntent(), raw, revised, confidence, status, source,
                reason, latency, calls, input, output);
    }

    private static Long totalUsage(Long a, Long b, int aCalls, int bCalls) {
        if ((aCalls > 0 && a == null) || (bCalls > 0 && b == null)) return null;
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }
    private static void requireKey(LocalIntentEvaluationConfig config, String name) {
        if (config.value(name, "").isBlank()) throw new IllegalArgumentException("Missing " + name + "; configure process environment or src/.env");
    }
    private static double probability(String value) {
        double p = Double.parseDouble(value);
        if (!Double.isFinite(p) || p < 0 || p > 1) throw new IllegalArgumentException("Invalid evaluation probability threshold");
        return p;
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    /** Safe local observation; never stores prompts, credentials, or provider error bodies in reports. */
    private static final class RecordingTrace extends AgentTraceService {
        private final ObjectMapper json;
        private final Duration timeout;
        private final ObservedIntentHttpTransport transport;
        int agentCalls; boolean validAgentResult; Long inputTokens, outputTokens;
        IntentResult jevResult; String fallbackReason;
        RecordingTrace(ObjectMapper json, Duration timeout, ObservedIntentHttpTransport transport) {
            super(null, json); this.json = json; this.timeout = timeout; this.transport = transport;
        }
        void clear() { agentCalls = 0; validAgentResult = false; inputTokens = null; outputTokens = null; jevResult = null; fallbackReason = null; }
        @Override public Msg callAgent(String session, String name, String model, ReActAgent agent, String text) {
            agentCalls++;
            try {
                Msg response = agent.call(Msg.builder().role(MsgRole.USER).textContent(text).build()).block(timeout);
                if (response != null) {
                    try {
                        var root = new LlmJsonService(json).parseObject(response.getTextContent());
                        Intent.valueOf(root.path("intent").asText()); validAgentResult = true;
                    } catch (Exception ignored) { /* Not a valid model prediction; keep service rule fallback visible. */ }
                    if (transport.attempts() == 1 && transport.completeUsagePresent() && response.getChatUsage() != null) {
                        inputTokens = (long) response.getChatUsage().getInputTokens();
                        outputTokens = (long) response.getChatUsage().getOutputTokens();
                    }
                }
                return response;
            } catch (RuntimeException error) {
                throw new IllegalStateException("evaluation_agent_call_failed");
            }
        }
        @Override public void recordEvent(String type, String phase, Object input, Object output) { capture(type, output); }
        @Override public void recordEvent(String type, String phase, Object input, Object output, Long latency) { capture(type, output); }
        private void capture(String type, Object output) {
            if (type.equals("JEV_INTENT_DECISION") && output instanceof IntentResult result) jevResult = result;
            if (type.equals("JEV_INTENT_FALLBACK") && output instanceof Map<?, ?> map) {
                String reason = String.valueOf(map.get("reason"));
                fallbackReason = reason.matches("(?:low_confidence|jev_[a-z0-9_]+)") ? reason : "jev_exception";
            }
        }
    }

    private static final class RecordingClient implements JevClient {
        private final JevClient delegate;
        int calls; long latencyMs; Long inputTokens, outputTokens;
        RecordingClient(JevClient delegate) { this.delegate = delegate; }
        void clear() { calls = 0; latencyMs = 0; inputTokens = null; outputTokens = null; }
        @Override public JevResponse systemOne(Object state, Map<String, Object> questions) {
            calls++; long start = System.nanoTime();
            try {
                JevResponse response = delegate.systemOne(state, questions);
                if (response.usage() != null) {
                    inputTokens = response.usage().inputTokens(); outputTokens = response.usage().outputTokens();
                }
                return response;
            } finally { latencyMs = elapsed(start); }
        }
    }
}
