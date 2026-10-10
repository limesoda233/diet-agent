package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 已确定菜品目标后的检索文本归一化：修正错别字、别名和口语，不负责多轮指代。
 * 只有显式开启配置才使用本实现；每次一次无历史、无工具的轻量模型调用。
 * JSON 严格校验，约束复制原值；同步异常、超时和解析失败都回退原查询。
 * rewriteDetailed 为评测提供状态，防止把失败回退误算成模型正常“不改写”。
 */
public final class AgentScopeRecipeQueryRewriter implements RecipeQueryRewriter {
    private static final int MAX_QUERY_LENGTH = 500;
    private static final String SYSTEM_PROMPT = """
            你是中文菜谱检索查询改写器。你的任务是把用户原话改成更适合在 HowToCook 菜谱库中检索的短查询。
            可以修正明显错别字、统一常见食材别名和口语说法，并保留菜名、做法、工具、时间及否定条件。
            如果用户只给了一组食材，不要猜成唯一菜名；不要回答菜谱，不要补充用户没说的条件。
            只输出一个 JSON 对象，格式严格为 {"query":"改写后的查询"}，不得输出其他字段或解释。
            """;

    private final Model model;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final AgentTraceService traceService;

    public AgentScopeRecipeQueryRewriter(Model model,
                                         ObjectMapper objectMapper,
                                         Duration timeout,
                                         AgentTraceService traceService) {
        this.model = model;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.traceService = traceService;
    }

    @Override
    public Mono<RecipeQuery> rewrite(RecipeQuery original) {
        return rewriteDetailed(original).map(RewriteResult::query);
    }

    public Mono<RewriteResult> rewriteDetailed(RecipeQuery original) {
        // 在请求上下文仍绑定时捕获 Trace scope；异步模型返回或超时后临时恢复它。
        long startedAt = System.nanoTime();
        Consumer<TraceOutcome> trace = traceRecorder(original, startedAt);
        GenerateOptions options = GenerateOptions.builder()
                .stream(false)
                .temperature(0.0)
                .maxTokens(128)
                .executionConfig(ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build())
                .build();
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(SYSTEM_PROMPT).build(),
                Msg.builder().role(MsgRole.USER).textContent(original.text()).build());

        return Mono.defer(() -> model.stream(messages, List.of(), options)
                        .flatMapIterable(ChatResponse::getContent)
                        .ofType(TextBlock.class)
                        .map(TextBlock::getText)
                        .collect(Collectors.joining()))
                .map(this::parseQueryText)
                .map(text -> new RecipeQuery(text,
                        // 这些字段来自服务端契约，模型输出不允许增加、删掉或放松。
                        original.requiredIngredients(),
                        original.excludedIngredients(),
                        original.requiredTools(),
                        original.maxMinutes()))
                .timeout(timeout)
                .map(rewritten -> new RewriteResult(rewritten,
                        rewritten.text().equals(original.text()) ? "UNCHANGED" : "REWRITTEN",
                        null))
                .onErrorResume(error -> {
                    return Mono.just(new RewriteResult(original, "FALLBACK", errorType(error)));
                })
                .doOnNext(result -> trace.accept(new TraceOutcome(
                        result.status(), result.query().text(), result.errorType())));
    }

    private String parseQueryText(String response) {
        String json = unwrapFence(response == null ? "" : response.trim());
        try (JsonParser parser = objectMapper.createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || root.size() != 1 || parser.nextToken() != null) {
                throw new IllegalArgumentException("rewrite response must contain one JSON object");
            }
            JsonNode query = root.get("query");
            if (query == null || !query.isTextual()) {
                throw new IllegalArgumentException("rewrite response is missing textual query");
            }
            String text = query.textValue().trim();
            if (text.isBlank() || text.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("rewrite query length is invalid");
            }
            return text;
        } catch (IOException error) {
            throw new IllegalArgumentException("rewrite response is not valid JSON", error);
        }
    }

    private String unwrapFence(String response) {
        if (!response.startsWith("```")) return response;
        int firstLineEnd = response.indexOf('\n');
        if (firstLineEnd < 0 || !response.endsWith("```")) {
            throw new IllegalArgumentException("rewrite response has an incomplete code fence");
        }
        return response.substring(firstLineEnd + 1, response.length() - 3).trim();
    }

    private Consumer<TraceOutcome> traceRecorder(RecipeQuery original, long startedAt) {
        if (traceService == null) return ignored -> { };
        AtomicReference<TraceOutcome> outcome = new AtomicReference<>();
        try {
            Supplier<Void> recorder = traceService.propagate(() -> {
                TraceOutcome value = outcome.get();
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("status", value.status());
                output.put("effectiveQuery", value.effectiveQuery());
                output.put("model", model.getModelName());
                if (value.errorType() != null) output.put("errorType", value.errorType());
                traceService.recordEvent(
                        "RECIPE_QUERY_REWRITE",
                        "RETRIEVAL",
                        Map.of("originalQuery", original.text()),
                        output,
                        (System.nanoTime() - startedAt) / 1_000_000);
                return null;
            });
            return value -> {
                outcome.set(value);
                try {
                    recorder.get();
                } catch (RuntimeException ignored) {
                    // Observability must never turn a successful rewrite into a retrieval failure.
                }
            };
        } catch (RuntimeException ignored) {
            return unused -> { };
        }
    }

    private String errorType(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof RuntimeException || current instanceof TimeoutException)) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private record TraceOutcome(String status, String effectiveQuery, String errorType) { }

    public record RewriteResult(RecipeQuery query, String status, String errorType) { }
}
