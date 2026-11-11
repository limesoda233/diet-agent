package com.diet.evaluation;

import com.diet.enums.Intent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluation-only metrics. Failed calls remain in the denominator; challenge scores stay separate. */
final class IntentComparisonReport {
    record Row(String id, String dataset, String arm, int repeat, String slice, String utterance,
               Intent expectedAgentIntent, Intent expectedFinalIntent, Intent predictedIntent,
               Intent finalIntent, Double confidence, String status, String source, String fallbackReason,
               long latencyMs, int modelCalls, Long inputTokens, Long outputTokens) {}
    record ClassMetric(long support, long precisionDenominator, double precision, double recall, double f1) {}
    record CalibrationBin(int count, Double averageConfidence, Double accuracy) {}
    record Metric(int attempts, long correct, double accuracy, double agentAccuracy, double macroF1,
                  long failures, Double successOnlyAccuracy, long latencyP50Ms, long latencyP95Ms,
                  Map<String, ClassMetric> perClass, Map<String, Map<String, Long>> confusion,
                  long highConfidenceErrors, int calibrationCount, Double ece,
                  Map<String, CalibrationBin> calibration, Map<String, Long> sources,
                  Map<String, Long> fallbackReasons, int modelCalls, long usageAvailableRows,
                  Long inputTokens, Long outputTokens) {}
    record Paired(int pairs, long wins, long losses, long bothCorrect, long bothWrong, Double deltaAccuracyPp) {}

    static Map<String, Metric> build(List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        rows.forEach(row -> groups.computeIfAbsent(row.dataset() + "/" + row.arm(), ignored -> new ArrayList<>()).add(row));
        Map<String, Metric> result = new LinkedHashMap<>();
        groups.forEach((key, values) -> result.put(key, summarize(values)));
        return result;
    }

    static Metric summarize(List<Row> rows) {
        long correct = rows.stream().filter(IntentComparisonReport::correct).count();
        long agentCorrect = rows.stream().filter(r -> r.predictedIntent() == r.expectedAgentIntent()).count();
        var successes = rows.stream().filter(r -> r.status().equals("OK")).toList();
        Map<String, ClassMetric> classes = new LinkedHashMap<>();
        Map<String, Map<String, Long>> confusion = new LinkedHashMap<>();
        double f1Sum = 0; int supportedClasses = 0;
        for (Intent label : Intent.values()) {
            long support = rows.stream().filter(r -> r.expectedFinalIntent() == label).count();
            long predictions = rows.stream().filter(r -> r.finalIntent() == label).count();
            long tp = rows.stream().filter(r -> r.expectedFinalIntent() == label && r.finalIntent() == label).count();
            double precision = ratio(tp, predictions), recall = ratio(tp, support);
            double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
            classes.put(label.name(), new ClassMetric(support, predictions, percent(precision), percent(recall), percent(f1)));
            if (support > 0) { supportedClasses++; f1Sum += f1; }
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Intent predicted : Intent.values()) counts.put(predicted.name(), rows.stream()
                    .filter(r -> r.expectedFinalIntent() == label && r.finalIntent() == predicted).count());
            counts.put("NO_PREDICTION", rows.stream().filter(r -> r.expectedFinalIntent() == label && r.finalIntent() == null).count());
            confusion.put(label.name(), counts);
        }
        var confidences = rows.stream().filter(r -> r.predictedIntent() != null && r.confidence() != null
                && Double.isFinite(r.confidence()) && r.confidence() >= 0 && r.confidence() <= 1).toList();
        Map<String, CalibrationBin> bins = new LinkedHashMap<>();
        double ece = 0;
        for (int index = 0; index < 5; index++) {
            int bin = index;
            var selected = confidences.stream().filter(r -> Math.min(4, (int) (r.confidence() * 5)) == bin).toList();
            Double average = selected.isEmpty() ? null : selected.stream().mapToDouble(Row::confidence).average().orElseThrow();
            Double accuracy = selected.isEmpty() ? null : ratio(selected.stream()
                    .filter(r -> r.predictedIntent() == r.expectedAgentIntent()).count(), selected.size());
            bins.put(String.format(Locale.ROOT, "%.1f-%.1f", index * 0.2, (index + 1) * 0.2),
                    new CalibrationBin(selected.size(), average, accuracy));
            if (average != null) ece += selected.size() * Math.abs(average - accuracy) / confidences.size();
        }
        Map<String, Long> sources = new LinkedHashMap<>(), reasons = new LinkedHashMap<>();
        rows.forEach(r -> { sources.merge(r.source(), 1L, Long::sum);
            if (r.fallbackReason() != null) reasons.merge(r.fallbackReason(), 1L, Long::sum); });
        var usage = rows.stream().filter(r -> r.inputTokens() != null && r.outputTokens() != null).toList();
        var latencies = rows.stream().map(Row::latencyMs).sorted().toList();
        return new Metric(rows.size(), correct, percent(ratio(correct, rows.size())), percent(ratio(agentCorrect, rows.size())),
                percent(ratioValue(f1Sum, supportedClasses)), rows.size() - successes.size(),
                successes.isEmpty() ? null : percent(ratio(successes.stream().filter(IntentComparisonReport::correct).count(), successes.size())),
                percentile(latencies, 0.5), percentile(latencies, 0.95), classes, confusion,
                confidences.stream().filter(r -> r.confidence() >= 0.8 && r.predictedIntent() != r.expectedAgentIntent()).count(),
                confidences.size(), confidences.isEmpty() ? null : ece, bins, sources, reasons,
                rows.stream().mapToInt(Row::modelCalls).sum(), usage.size(),
                usage.isEmpty() ? null : usage.stream().mapToLong(Row::inputTokens).sum(),
                usage.isEmpty() ? null : usage.stream().mapToLong(Row::outputTokens).sum());
    }

    static Paired paired(List<Row> rows, String dataset, String arm) {
        Map<String, Row> baseline = new LinkedHashMap<>();
        rows.stream().filter(r -> r.dataset().equals(dataset) && r.arm().equals("BASELINE"))
                .forEach(r -> baseline.put(r.repeat() + "/" + r.id(), r));
        int pairs = 0; long wins = 0, losses = 0, bothCorrect = 0, bothWrong = 0;
        for (Row row : rows) {
            if (!row.dataset().equals(dataset) || !row.arm().equals(arm)) continue;
            Row base = baseline.get(row.repeat() + "/" + row.id());
            if (base == null) continue;
            pairs++;
            if (correct(row) && correct(base)) bothCorrect++;
            else if (correct(row)) wins++;
            else if (correct(base)) losses++;
            else bothWrong++;
        }
        return new Paired(pairs, wins, losses, bothCorrect, bothWrong,
                pairs == 0 ? null : percent(ratioValue(wins - losses, pairs)));
    }

    static String markdown(Map<String, Object> metadata, List<Row> rows) {
        StringBuilder text = new StringBuilder("# 意图识别对照评测\n\n");
        metadata.forEach((name, value) -> text.append("- ").append(name).append(": ").append(value).append('\n'));
        text.append("\nJEV_RAW 从同一次生产链路调用中截取，未另发请求；其耗时只包含 Jev 分支。BASELINE 和 JEV_CHAIN 为独立实际调用。\n")
                .append("失败保留在分母中；Macro-F1 使用本组有 Gold 支持的类别。此处最终路由仅指 IntentReviseService 后的结果，不包含入口规则或整段聊天。\n")
                .append("Token 仅汇总提供完整 usage 的行；缺失为 null。三组调用数不能相加，因为 RAW 和 CHAIN 共享 Jev 调用。\n\n")
                .append("| 数据集/路径 | N | 原始意图准确率 | 修正后准确率 | Macro-F1 | 失败/规则兜底 | P50/P95 ms | 模型调用数 | usage覆盖 |\n")
                .append("| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | ---: |\n");
        build(rows).forEach((key, m) -> text.append("| ").append(key).append(" | ").append(m.attempts())
                .append(" | ").append(m.agentAccuracy()).append("% | ").append(m.accuracy()).append("% | ")
                .append(m.macroF1()).append("% | ").append(m.failures()).append(" | ").append(m.latencyP50Ms())
                .append('/').append(m.latencyP95Ms()).append(" | ").append(m.modelCalls()).append(" | ")
                .append(m.usageAvailableRows()).append('/').append(m.attempts()).append(" |\n"));
        for (String dataset : rows.stream().map(Row::dataset).distinct().toList()) {
            text.append("\n## ").append(dataset).append(" 配对差异\n\n");
            for (String arm : List.of("JEV_RAW", "JEV_CHAIN")) {
                Paired p = paired(rows, dataset, arm);
                if (p.pairs() == 0) { text.append("- ").append(arm).append(": 无配对结果，差异不可计算。\n"); continue; }
                text.append("- ").append(arm).append(" vs BASELINE: ").append(p.pairs()).append(" 对；赢 ")
                        .append(p.wins()).append("，输 ").append(p.losses()).append("，都对 ").append(p.bothCorrect())
                        .append("，都错 ").append(p.bothWrong()).append("；准确率差 ").append(p.deltaAccuracyPp()).append(" 个百分点。\n");
            }
        }
        text.append("\n## 接受、回退和置信度\n\n");
        build(rows).forEach((key, m) -> text.append("- ").append(key).append(": 来源 ").append(m.sources())
                .append("；回退原因 ").append(m.fallbackReasons()).append("；高分错误 ").append(m.highConfidenceErrors())
                .append("；ECE ").append(m.ece()).append("（有效分数 ").append(m.calibrationCount()).append("）。\n"));
        text.append("\n## 重复运行波动\n\n未完成运行或每轮样本数不同，仅可查看检查点，不能解释为稳定性；一轮标准差为零也不表示稳定。\n\n| 集合/路径 | 已运行轮数 | 每轮样本范围 | 准确率均值 | 总体标准差 | 最差一次 |\n| --- | ---: | --- | ---: | ---: | ---: |\n");
        repeatVariation(rows).forEach((key, v) -> text.append("| ").append(key).append(" | ").append(v.runs())
                .append(" | ").append(v.minCasesPerRun()).append('-').append(v.maxCasesPerRun())
                .append(" | ").append(v.meanAccuracy()).append("% | ").append(v.populationStdDev())
                .append(" | ").append(v.worstAccuracy()).append("% |\n"));
        text.append("\nECE 只作分箱诊断；小样本与不同分数定义不能证明置信度已校准。JSON 保留混淆矩阵、分标签指标、置信度分箱及逐条结果。\n")
                .append("\n## 错误及无预测样本\n\n| ID/轮次 | 集合 | 路径 | Gold | 原始/修正后 | 状态 | 输入 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        rows.stream().filter(r -> !correct(r) || r.predictedIntent() != r.expectedAgentIntent())
                .forEach(r -> text.append("| ").append(r.id()).append('/').append(r.repeat()).append(" | ")
                        .append(r.dataset()).append(" | ").append(r.arm()).append(" | ").append(r.expectedFinalIntent())
                        .append(" | ").append(r.predictedIntent()).append('/').append(r.finalIntent()).append(" | ")
                        .append(r.status()).append(" | ").append(r.utterance().replace("|", "\\|").replace("\n", " ")).append(" |\n"));
        return text.toString();
    }

    private static boolean correct(Row r) { return r.finalIntent() == r.expectedFinalIntent(); }
    record Variation(int runs, double meanAccuracy, double populationStdDev, double worstAccuracy,
                     int minCasesPerRun, int maxCasesPerRun) {}
    static Map<String, Variation> repeatVariation(List<Row> rows) {
        Map<String, Variation> result = new LinkedHashMap<>();
        for (String key : build(rows).keySet()) {
            var group = rows.stream().filter(r -> (r.dataset() + "/" + r.arm()).equals(key)).toList();
            var accuracies = group.stream().map(Row::repeat).distinct().map(repeat -> summarize(group.stream()
                    .filter(r -> r.repeat() == repeat).toList()).accuracy()).toList();
            double mean = accuracies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = accuracies.stream().mapToDouble(a -> (a - mean) * (a - mean)).average().orElse(0);
            var sizes = group.stream().map(Row::repeat).distinct().mapToInt(repeat -> (int) group.stream()
                    .filter(r -> r.repeat() == repeat).count()).summaryStatistics();
            result.put(key, new Variation(accuracies.size(), Math.round(mean * 100) / 100.0,
                    Math.round(Math.sqrt(variance) * 100) / 100.0,
                    accuracies.stream().mapToDouble(Double::doubleValue).min().orElse(0), sizes.getMin(), sizes.getMax()));
        }
        return result;
    }
    private static double ratio(long numerator, long denominator) { return denominator == 0 ? 0 : (double) numerator / denominator; }
    private static double ratioValue(double numerator, long denominator) { return denominator == 0 ? 0 : numerator / denominator; }
    private static double percent(double value) { return Math.round(value * 10_000.0) / 100.0; }
    private static long percentile(List<Long> values, double q) { return values.isEmpty() ? 0 : values.get((int) Math.ceil(values.size() * q) - 1); }
}
