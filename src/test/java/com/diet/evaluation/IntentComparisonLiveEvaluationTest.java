package com.diet.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in live experiment. Ordinary mvn test never loads credentials or calls either provider. */
@EnabledIfSystemProperty(named = "intent.compare.live", matches = "true")
class IntentComparisonLiveEvaluationTest {
    @Test
    void comparesFrozenInputsAndCapturesJevBeforeFallback() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path envFile = Path.of(System.getProperty("intent.compare.env-file", "src/.env"));
        var config = LocalIntentEvaluationConfig.load(envFile, System.getenv());
        int repeats = Integer.parseInt(System.getProperty("intent.compare.repeats", "1"));
        int limit = Integer.parseInt(System.getProperty("intent.compare.limit", "0"));
        if (repeats < 1 || repeats > 10 || limit < 0) throw new IllegalArgumentException("Invalid evaluation repeats/limit");
        boolean challenge = Boolean.parseBoolean(System.getProperty("intent.compare.challenge", "true"));
        boolean baselineOnly = Boolean.parseBoolean(System.getProperty("intent.compare.baseline-only", "false"));
        var runtime = new IntentComparisonRuntime(config, !baselineOnly);
        List<Example> examples = new ArrayList<>();
        for (var c : IntentEvaluationDataset.load(json)) examples.add(new Example("main", c));
        if (challenge) {
            try (var input = getClass().getClassLoader().getResourceAsStream("evaluation/intent-challenge-v1.jsonl")) {
                if (input == null) throw new IllegalStateException("Missing challenge dataset");
                for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList())
                    if (!line.isBlank()) examples.add(new Example("challenge", json.readValue(line, IntentEvaluationDataset.Case.class)));
            }
        }
        if (limit > 0 && limit < examples.size()) examples = new ArrayList<>(examples.subList(0, limit));
        String runId = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path output = Path.of("target", "evaluation", "intent-comparison", runId);
        Files.createDirectories(output);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId);
        metadata.put("startedAt", ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        metadata.put("deepSeekModel", runtime.deepSeekModel); metadata.put("jevModel", runtime.jevModel);
        metadata.put("jevConfidenceThreshold", runtime.threshold); metadata.put("slotProbabilityThreshold", runtime.slotThreshold);
        metadata.put("jevTimeout", runtime.jevTimeout.toString()); metadata.put("agentTimeout", runtime.agentTimeout.toString());
        metadata.put("repeats", repeats); metadata.put("limitedRun", limit > 0);
        metadata.put("mode", baselineOnly ? "baseline_only" : "comparison");
        metadata.put("javaNetUseSystemProxies", Boolean.parseBoolean(System.getProperty("java.net.useSystemProxies", "false")));
        metadata.put("plannedPairs", examples.size() * repeats);
        metadata.put("datasetSha256", hash(Path.of("src/test/resources/evaluation/intent-eval.jsonl")));
        metadata.put("challengeSha256", hash(Path.of("src/test/resources/evaluation/intent-challenge-v1.jsonl")));
        metadata.put("promptSha256", hash(Path.of("src/main/resources/diet/prompts/intent.txt")));
        metadata.put("jevCriteriaSha256", hash(Path.of("src/main/java/com/diet/service/jev/JevIntentRecognitionService.java")));
        metadata.put("jevClientSha256", hash(Path.of("src/main/java/com/diet/service/jev/HttpJevClient.java")));
        metadata.put("evaluationRuntimeSha256", hash(Path.of("src/test/java/com/diet/evaluation/IntentComparisonRuntime.java")));
        metadata.put("revisionSha256", hash(Path.of("src/main/java/com/diet/service/intent/IntentReviseService.java")));
        metadata.put("slotDictionarySha256", hash(json.writeValueAsBytes(IntentEvaluationDataset.slotOptions())));
        metadata.put("order", baselineOnly ? "baseline only, dataset order" : "baseline/chain alternate for each pair and repeat; RAW captured from CHAIN");
        metadata.put("rawLatencyScope", "HTTP Jev call and response parsing; excludes question construction and revision");
        metadata.put("callCountScope", "client transport HTTP attempts, not Agent invocations or Flux resubscriptions");
        var defaults = io.agentscope.core.model.ExecutionConfig.MODEL_DEFAULTS;
        metadata.put("sdkConfiguredMaxAttempts", defaults.getMaxAttempts());
        metadata.put("sdkConfiguredTimeout", defaults.getTimeout().toString());
        metadata.put("sdkConfiguredInitialBackoff", defaults.getInitialBackoff().toString());
        metadata.put("sdkConfiguredMaxBackoff", defaults.getMaxBackoff().toString());
        metadata.put("sdkConfiguredBackoffMultiplier", defaults.getBackoffMultiplier());
        metadata.put("retryNote", "SDK defaults preserved; configured policy does not imply every 5xx is retried");
        List<IntentComparisonReport.Row> rows = new ArrayList<>();
        int errors = 0, completed = 0; String abortReason = null;
        run: for (int repeat = 1; repeat <= repeats; repeat++) {
            for (Example example : examples) {
                var paired = baselineOnly ? runtime.evaluateBaseline(example.value(), example.dataset(), repeat)
                        : runtime.evaluate(example.value(), example.dataset(), repeat, (completed + repeat) % 2 == 0);
                rows.addAll(paired); completed++;
                var baseline = paired.get(0); var raw = baselineOnly ? null : paired.get(1);
                boolean externalFailure = !baseline.status().equals("OK") || (raw != null && !raw.status().equals("OK"));
                errors = externalFailure ? errors + 1 : 0;
                metadata.put("completedPairs", completed); metadata.put("complete", false);
                save(json, output, metadata, rows); // Checkpoint each pair so an interruption cannot lose the experiment.
                System.out.printf("Intent comparison %d/%d: baseline=%s, Jev=%s%n", completed,
                        examples.size() * repeats, baseline.status(), raw == null ? "not_requested" : raw.status());
                if (raw != null && raw.fallbackReason() != null && raw.fallbackReason().matches("jev_http_(401|403|404|451)")) {
                    abortReason = "Jev authentication/access/endpoint error: " + raw.fallbackReason(); break run;
                }
                if (errors >= 3) { abortReason = "Three consecutive provider failures; investigate connectivity before continuing"; break run; }
            }
        }
        metadata.put("complete", abortReason == null && completed == examples.size() * repeats);
        metadata.put("comparisonReady", !baselineOnly && abortReason == null
                && rows.stream().anyMatch(r -> r.arm().equals("JEV_RAW") && r.status().equals("OK"))
                && rows.stream().anyMatch(r -> r.arm().equals("BASELINE") && r.status().equals("OK")));
        if (abortReason != null) metadata.put("abortReason", abortReason);
        save(json, output, metadata, rows);
        System.out.println("Intent comparison report: " + output.toAbsolutePath().resolve("report.md"));
        assertThat(abortReason).as("Live run incomplete; see the saved report").isNull();
        assertThat(rows.stream().filter(r -> r.arm().equals("BASELINE") && r.status().equals("OK")).count()).isPositive();
        if (!baselineOnly) assertThat(rows.stream().filter(r -> r.arm().equals("JEV_RAW") && r.status().equals("OK")).count()).isPositive();
        // No accuracy gate: this is measurement, not prompt tuning or a preselected winning run.
    }

    private static void save(ObjectMapper json, Path output, Map<String, Object> metadata,
                             List<IntentComparisonReport.Row> rows) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("metadata", metadata); report.put("metrics", IntentComparisonReport.build(rows));
        report.put("repeatVariation", IntentComparisonReport.repeatVariation(rows));
        report.put("paired", rows.stream().map(IntentComparisonReport.Row::dataset).distinct().collect(
                java.util.stream.Collectors.toMap(d -> d, d -> Map.of(
                        "JEV_RAW", IntentComparisonReport.paired(rows, d, "JEV_RAW"),
                        "JEV_CHAIN", IntentComparisonReport.paired(rows, d, "JEV_CHAIN")))));
        report.put("byRepeat", rows.stream().map(IntentComparisonReport.Row::repeat).distinct().collect(
                java.util.stream.Collectors.toMap(r -> r, r -> IntentComparisonReport.build(rows.stream().filter(v -> v.repeat() == r).toList()))));
        report.put("bySlice", rows.stream().map(r -> r.dataset() + "/" + r.slice()).distinct().collect(
                java.util.stream.Collectors.toMap(s -> s, s -> IntentComparisonReport.build(rows.stream()
                        .filter(r -> (r.dataset() + "/" + r.slice()).equals(s)).toList()))));
        report.put("rows", rows);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(), report);
        Files.writeString(output.resolve("report.md"), IntentComparisonReport.markdown(metadata, rows), StandardCharsets.UTF_8);
    }
    private static String hash(Path path) throws Exception { return hash(Files.readAllBytes(path)); }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private record Example(String dataset, IntentEvaluationDataset.Case value) {}
}
