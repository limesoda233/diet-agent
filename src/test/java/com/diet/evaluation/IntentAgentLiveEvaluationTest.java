package com.diet.evaluation;

import com.diet.enums.Intent;
import com.diet.enums.SourceMode;
import com.diet.mapper.SlotOptionMapper;
import com.diet.model.IntentResult;
import com.diet.model.SessionState;
import com.diet.model.SlotBundle;
import com.diet.service.intent.IntentAgentService;
import com.diet.service.intent.IntentReviseService;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.mockito.invocation.Invocation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.hikari.initialization-fail-timeout=-1",
                "spring.datasource.hikari.minimum-idle=0"
        }
)
@EnabledIfSystemProperty(named = "intent.eval.live", matches = "true")
class IntentAgentLiveEvaluationTest {
    private static final Path OUTPUT_DIR = Path.of("target", "evaluation");

    @Autowired
    private IntentAgentService intentAgentService;

    @Autowired
    private IntentReviseService intentReviseService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SlotOptionMapper slotOptionMapper;

    @SpyBean
    private AgentTraceService agentTraceService;

    @Value("${agentscope.deepseek.api-key:}")
    private String apiKey;

    @Value("${diet.llm.light-model:deepseek-chat}")
    private String modelName;

    @Value("${diet.jev.api-key:}")
    private String jevApiKey;

    @Value("${diet.jev.model:jev-latest}")
    private String jevModel;

    @Value("${diet.jev.intent-enabled:true}")
    private boolean jevIntentEnabled;

    @Test
    void evaluatesVersionedGoldDatasetAgainstLiveModel() throws Exception {
        assertThat(hasJevCredential() || hasDeepSeekCredential())
                .as("真实模型评测需要设置 JEV_API_KEY 或 DEEPSEEK_API_KEY")
                .isTrue();

        Map<String, List<String>> options = IntentEvaluationDataset.slotOptions();
        when(slotOptionMapper.findEnabledValues(anyString()))
                .thenAnswer(invocation -> options.getOrDefault(invocation.getArgument(0), List.of()));

        List<IntentEvaluationDataset.Case> cases = IntentEvaluationDataset.load(objectMapper);
        List<CaseResult> results = new ArrayList<>();
        IntentRoutingStatsCollector routingStatsCollector = new IntentRoutingStatsCollector();
        int completed = 0;
        for (IntentEvaluationDataset.Case testCase : cases) {
            long startedAt = System.nanoTime();
            String sessionId = "intent-eval-" + testCase.id();
            int traceInvocationStart = traceInvocationCount();
            IntentResult agentResult = intentAgentService.recognize(
                    sessionId,
                    1L,
                    testCase.utterance(),
                    SlotBundle.empty(),
                    testCase.conversationHistory()
            );
            SessionState state = SessionState.fresh(sessionId, 1L, SourceMode.PUBLIC)
                    .withLastRecommendations(testCase.hasPreviousRecommendations() ? List.of(9001L) : List.of());
            IntentResult finalResult = intentReviseService.revise(state, agentResult, testCase.utterance());
            long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
            boolean fallbackSuspected = looksLikeFallback(agentResult);
            IntentDecisionSource decisionSource = routingStatsCollector.recordCase(
                    routingSignalsSince(traceInvocationStart),
                    fallbackSuspected
            );

            results.add(new CaseResult(
                    testCase.id(),
                    testCase.slice(),
                    testCase.utterance(),
                    testCase.expectedAgentIntent(),
                    agentResult.intent(),
                    testCase.expectedFinalIntent(),
                    finalResult.intent(),
                    agentResult.confidence(),
                    latencyMs,
                    fallbackSuspected,
                    decisionSource
            ));
            completed++;
            if (completed % 12 == 0 || completed == cases.size()) {
                System.out.printf("意图评测进度: %d/%d%n", completed, cases.size());
            }
        }

        EvaluationReport report = buildReport(results, routingStatsCollector.snapshot());
        Files.createDirectories(OUTPUT_DIR);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT_DIR.resolve("intent-evaluation-report.json").toFile(), report);
        String markdown = toMarkdown(report);
        Files.writeString(
                OUTPUT_DIR.resolve("intent-evaluation-report.md"),
                markdown,
                StandardCharsets.UTF_8
        );
        System.out.println(markdown);

        double minimumAccuracy = Double.parseDouble(
                System.getProperty("intent.eval.min-final-accuracy", "90.0")
        );
        double minimumHealthRiskRecall = Double.parseDouble(
                System.getProperty("intent.eval.min-health-risk-recall", "90.0")
        );
        assertThat(report.finalMetrics().accuracy()).isGreaterThanOrEqualTo(minimumAccuracy);
        assertThat(report.finalMetrics().perClass().get(Intent.HEALTH_RISK).recall())
                .isGreaterThanOrEqualTo(minimumHealthRiskRecall);
    }

    private EvaluationReport buildReport(List<CaseResult> results, IntentRoutingStats routingStats) {
        MetricSummary agentMetrics = summarize(
                results,
                CaseResult::expectedAgentIntent,
                CaseResult::predictedAgentIntent
        );
        MetricSummary finalMetrics = summarize(
                results,
                CaseResult::expectedFinalIntent,
                CaseResult::predictedFinalIntent
        );

        Map<String, SliceMetric> slices = new LinkedHashMap<>();
        results.stream().map(CaseResult::slice).distinct().sorted().forEach(slice -> {
            List<CaseResult> sliced = results.stream().filter(result -> result.slice().equals(slice)).toList();
            long correct = sliced.stream().filter(result -> result.expectedFinalIntent() == result.predictedFinalIntent()).count();
            slices.put(slice, new SliceMetric(sliced.size(), correct, percent(correct, sliced.size())));
        });

        List<Long> latencies = results.stream().map(CaseResult::latencyMs).sorted().toList();
        long fallbackCount = results.stream().filter(CaseResult::fallbackSuspected).count();
        List<CaseResult> finalErrors = results.stream()
                .filter(result -> result.expectedFinalIntent() != result.predictedFinalIntent())
                .toList();

        return new EvaluationReport(
                OffsetDateTime.now().toString(),
                activeModelName(),
                IntentEvaluationDataset.RESOURCE,
                results.size(),
                agentMetrics,
                finalMetrics,
                slices,
                routingStats,
                fallbackCount,
                percentile(latencies, 0.50),
                percentile(latencies, 0.95),
                finalErrors,
                results
        );
    }

    private MetricSummary summarize(
            List<CaseResult> results,
            Function<CaseResult, Intent> expected,
            Function<CaseResult, Intent> predicted
    ) {
        Map<Intent, PerClassMetric> perClass = new LinkedHashMap<>();
        Map<Intent, Map<Intent, Long>> confusion = new LinkedHashMap<>();
        long totalCorrect = results.stream().filter(result -> expected.apply(result) == predicted.apply(result)).count();
        double f1Sum = 0.0;

        for (Intent label : Intent.values()) {
            long support = results.stream().filter(result -> expected.apply(result) == label).count();
            long predictedCount = results.stream().filter(result -> predicted.apply(result) == label).count();
            long truePositive = results.stream()
                    .filter(result -> expected.apply(result) == label && predicted.apply(result) == label)
                    .count();
            double precision = ratio(truePositive, predictedCount);
            double recall = ratio(truePositive, support);
            double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
            f1Sum += f1;
            perClass.put(label, new PerClassMetric(
                    support,
                    predictedCount,
                    truePositive,
                    roundPercent(precision),
                    roundPercent(recall),
                    roundPercent(f1)
            ));

            Map<Intent, Long> row = new LinkedHashMap<>();
            for (Intent predictedLabel : Intent.values()) {
                long count = results.stream()
                        .filter(result -> expected.apply(result) == label && predicted.apply(result) == predictedLabel)
                        .count();
                row.put(predictedLabel, count);
            }
            confusion.put(label, row);
        }

        WilsonInterval confidenceInterval = wilsonInterval(totalCorrect, results.size());
        return new MetricSummary(
                results.size(),
                totalCorrect,
                percent(totalCorrect, results.size()),
                confidenceInterval.lowerPercent(),
                confidenceInterval.upperPercent(),
                roundPercent(f1Sum / Intent.values().length),
                perClass,
                confusion
        );
    }

    private String toMarkdown(EvaluationReport report) {
        StringBuilder text = new StringBuilder();
        text.append("# 意图识别离线评测报告\n\n");
        text.append("- 模型：`").append(report.model()).append("`\n");
        text.append("- 数据集：`").append(report.dataset()).append("`（").append(report.caseCount()).append(" 条）\n");
        text.append("- 原始 Agent Accuracy：").append(format(report.agentMetrics().accuracy())).append("%\n");
        text.append("- 最终路由 Accuracy：").append(format(report.finalMetrics().accuracy())).append("%\n");
        text.append("- 最终路由 Accuracy 95% Wilson CI：[")
                .append(format(report.finalMetrics().confidence95Lower())).append("%, ")
                .append(format(report.finalMetrics().confidence95Upper())).append("%]\n");
        text.append("- 最终路由 Macro-F1：").append(format(report.finalMetrics().macroF1())).append("%\n");
        text.append("- Jev 尝试：").append(report.routingStats().jevAttempts()).append(" 条\n");
        text.append("- Jev 直接采纳：").append(report.routingStats().jevAccepted()).append(" 条（")
                .append(format(report.routingStats().jevAcceptanceRate())).append("%）\n");
        text.append("- Jev 低置信度降级：").append(report.routingStats().jevLowConfidenceFallbacks()).append(" 条\n");
        text.append("- Jev 异常降级：").append(report.routingStats().jevExceptionFallbacks()).append(" 条\n");
        text.append("- DeepSeek 调用：").append(report.routingStats().deepSeekCalls()).append(" 条\n");
        text.append("- Java 规则兜底：").append(report.routingStats().ruleFallbacks()).append(" 条\n");
        text.append("- 单条延迟：P50 ").append(report.latencyP50Ms()).append(" ms，P95 ")
                .append(report.latencyP95Ms()).append(" ms\n\n");

        text.append("## 最终路由分标签指标\n\n");
        text.append("| 意图 | 样本数 | Precision | Recall | F1 |\n");
        text.append("| --- | ---: | ---: | ---: | ---: |\n");
        report.finalMetrics().perClass().forEach((intent, metric) -> text
                .append("| ").append(intent).append(" | ").append(metric.support())
                .append(" | ").append(format(metric.precision())).append("%")
                .append(" | ").append(format(metric.recall())).append("%")
                .append(" | ").append(format(metric.f1())).append("% |\n"));

        text.append("\n## 最终路由错误样本\n\n");
        if (report.finalErrors().isEmpty()) {
            text.append("无。\n");
        } else {
            text.append("| ID | 切片 | 决策来源 | 预期 | 实际 | 输入 |\n");
            text.append("| --- | --- | --- | --- | --- | --- |\n");
            report.finalErrors().forEach(result -> text
                    .append("| ").append(result.id())
                    .append(" | ").append(result.slice())
                    .append(" | ").append(result.decisionSource())
                    .append(" | ").append(result.expectedFinalIntent())
                    .append(" | ").append(result.predictedFinalIntent())
                    .append(" | ").append(escapeCell(result.utterance())).append(" |\n"));
        }
        return text.toString();
    }

    private int traceInvocationCount() {
        return mockingDetails(agentTraceService).getInvocations().size();
    }

    private List<IntentRoutingSignal> routingSignalsSince(int invocationStart) {
        List<Invocation> invocations = new ArrayList<>(mockingDetails(agentTraceService).getInvocations());
        return invocations.stream().skip(invocationStart).map(this::routingSignal)
                .filter(signal -> signal != null).toList();
    }

    private IntentRoutingSignal routingSignal(Invocation invocation) {
        Object[] arguments = invocation.getArguments();
        if ("callAgent".equals(invocation.getMethod().getName()) && arguments.length > 1
                && "IntentAgent".equals(arguments[1])) {
            return IntentRoutingSignal.DEEPSEEK_CALL;
        }
        if (!"recordEvent".equals(invocation.getMethod().getName()) || arguments.length < 4) {
            return null;
        }
        if ("JEV_INTENT_DECISION".equals(arguments[0])) {
            return IntentRoutingSignal.JEV_DECISION;
        }
        if (!"JEV_INTENT_FALLBACK".equals(arguments[0])) {
            return null;
        }
        Object outputPayload = arguments[3];
        if (outputPayload instanceof Map<?, ?> output
                && "low_confidence".equals(output.get("reason"))) {
            return IntentRoutingSignal.JEV_LOW_CONFIDENCE_FALLBACK;
        }
        return IntentRoutingSignal.JEV_EXCEPTION_FALLBACK;
    }

    private boolean hasJevCredential() {
        return jevIntentEnabled && jevApiKey != null && !jevApiKey.isBlank();
    }

    private boolean hasDeepSeekCredential() {
        return apiKey != null && !apiKey.isBlank();
    }

    private boolean looksLikeFallback(IntentResult result) {
        return Math.abs(result.confidence() - 0.2) < 0.000_001
                && (result.slots() == null || result.slots().isEmpty());
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private double percent(long numerator, long denominator) {
        return roundPercent(ratio(numerator, denominator));
    }

    private double roundPercent(double ratio) {
        return Math.round(ratio * 10_000.0) / 100.0;
    }

    private WilsonInterval wilsonInterval(long correct, long total) {
        if (total == 0) {
            return new WilsonInterval(0.0, 0.0);
        }
        double z = 1.96;
        double proportion = (double) correct / total;
        double denominator = 1.0 + z * z / total;
        double center = (proportion + z * z / (2.0 * total)) / denominator;
        double margin = z * Math.sqrt(
                proportion * (1.0 - proportion) / total + z * z / (4.0 * total * total)
        ) / denominator;
        return new WilsonInterval(
                roundPercent(Math.max(0.0, center - margin)),
                roundPercent(Math.min(1.0, center + margin))
        );
    }

    private long percentile(List<Long> sortedValues, double quantile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(quantile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String activeModelName() {
        return jevIntentEnabled && jevApiKey != null && !jevApiKey.isBlank()
                ? jevModel + " + " + modelName + " fallback"
                : modelName;
    }

    private String escapeCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    record CaseResult(
            String id,
            String slice,
            String utterance,
            Intent expectedAgentIntent,
            Intent predictedAgentIntent,
            Intent expectedFinalIntent,
            Intent predictedFinalIntent,
            double confidence,
            long latencyMs,
            boolean fallbackSuspected,
            IntentDecisionSource decisionSource
    ) {
    }

    record PerClassMetric(
            long support,
            long predicted,
            long correct,
            double precision,
            double recall,
            double f1
    ) {
    }

    record MetricSummary(
            int total,
            long correct,
            double accuracy,
            double confidence95Lower,
            double confidence95Upper,
            double macroF1,
            Map<Intent, PerClassMetric> perClass,
            Map<Intent, Map<Intent, Long>> confusionMatrix
    ) {
    }

    record WilsonInterval(double lowerPercent, double upperPercent) {
    }

    record SliceMetric(int total, long correct, double accuracy) {
    }

    record EvaluationReport(
            String generatedAt,
            String model,
            String dataset,
            int caseCount,
            MetricSummary agentMetrics,
            MetricSummary finalMetrics,
            Map<String, SliceMetric> slices,
            IntentRoutingStats routingStats,
            long fallbackCount,
            long latencyP50Ms,
            long latencyP95Ms,
            List<CaseResult> finalErrors,
            List<CaseResult> caseResults
    ) {
    }
}
