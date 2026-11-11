package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.retrieval.RecipeRerankResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** Paired diagnostic metrics. Provider failures are never counted as successful OOD rejection. */
final class RecipeRerankEvaluationReport {
    record Observation(String queryId, int repeat, List<String> candidates, long retrievalMs,
                       boolean retrievalFailed, RecipeRerankResult.Status scoringStatus,
                       List<RecipeRerankResult.CandidateScore> scores, long scoringMs,
                       String errorCode, String queryHash, List<String> documentHashes,
                       String actualRetrievalMode, List<String> retrievalDegradationReasons) {
        Observation {
            candidates = List.copyOf(candidates);
            scores = List.copyOf(scores);
            documentHashes = List.copyOf(documentHashes);
            retrievalDegradationReasons = List.copyOf(retrievalDegradationReasons);
        }
        Observation(String queryId, int repeat, List<String> candidates, long retrievalMs,
                    boolean retrievalFailed, RecipeRerankResult.Status scoringStatus,
                    List<RecipeRerankResult.CandidateScore> scores, long scoringMs,
                    String errorCode, String queryHash, List<String> documentHashes) {
            this(queryId, repeat, candidates, retrievalMs, retrievalFailed, scoringStatus,
                    scores, scoringMs, errorCode, queryHash, documentHashes, "PARALLEL_HYBRID", List.of());
        }

        boolean retrievalDegraded() {
            return !retrievalFailed && (!retrievalDegradationReasons.isEmpty()
                    || !"PARALLEL_HYBRID".equals(actualRetrievalMode));
        }
        boolean paired() {
            return !retrievalFailed && !retrievalDegraded() && (scoringStatus == RecipeRerankResult.Status.ACCEPTED
                    || scoringStatus == RecipeRerankResult.Status.FILTERED);
        }
    }

    static List<RecipeEvaluationDataset.Example> selectExamples(String split, boolean frozen,
                                                                String calibrationId, Double threshold) {
        if (!("dev".equals(split) || "test".equals(split))) throw new IllegalArgumentException("split must be dev or test");
        if (threshold != null && !Double.isFinite(threshold)) throw new IllegalArgumentException("threshold must be finite");
        if ("test".equals(split) && (!frozen || threshold == null || calibrationId == null || calibrationId.isBlank()))
            throw new IllegalArgumentException("test requires frozen=true, min-score and calibration-run-id chosen on dev");
        return RecipeEvaluationDataset.load("v2").examples().stream().filter(value -> split.equals(value.split())).toList();
    }

    static Map<String, Object> build(Map<String, Object> metadata, List<Observation> observations, Double threshold) {
        var dataset = RecipeEvaluationDataset.load("v2");
        Map<String, RecipeEvaluationDataset.Example> examples = new LinkedHashMap<>();
        dataset.examples().forEach(example -> examples.put(example.queryId(), example));
        List<Observation> paired = observations.stream().filter(Observation::paired).toList();
        int retrievalFailures = (int) observations.stream().filter(Observation::retrievalFailed).count();
        int retrievalDegradations = (int) observations.stream().filter(Observation::retrievalDegraded).count();
        int scoringFailures = (int) observations.stream().filter(row -> !row.retrievalFailed()
                && row.scoringStatus() != RecipeRerankResult.Status.ACCEPTED
                && row.scoringStatus() != RecipeRerankResult.Status.FILTERED).count();
        int planned = ((Number) metadata.get("plannedObservations")).intValue();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("metadata", metadata);
        report.put("processedObservations", observations.size());
        report.put("complete", observations.size() == planned);
        report.put("comparisonReady", planned > 0 && observations.size() == planned
                && retrievalFailures == 0 && retrievalDegradations == 0 && scoringFailures == 0 && threshold != null);
        report.put("retrievalFailures", retrievalFailures);
        report.put("retrievalDegradations", retrievalDegradations);
        report.put("scoringFailures", scoringFailures);
        report.put("pairedObservations", paired.size());
        report.put("metricScope", "same successfully scored candidates with healthy parallel retrieval; failures/degradations excluded and separately reported; no answer generation");
        report.put("threshold", threshold);
        report.put("pairedMetrics", arms(paired, examples, threshold));
        Map<String,Object> bySlice = new LinkedHashMap<>();
        paired.stream().map(row -> examples.get(row.queryId()).slice()).distinct().forEach(slice ->
                bySlice.put(slice, arms(paired.stream().filter(row -> examples.get(row.queryId()).slice().equals(slice)).toList(), examples, threshold)));
        report.put("bySlice", bySlice);
        Map<String,Object> byRepeat = new LinkedHashMap<>();
        observations.stream().map(Observation::repeat).distinct().forEach(repeat ->
                byRepeat.put(repeat.toString(), arms(paired.stream().filter(row -> row.repeat() == repeat).toList(), examples, threshold)));
        report.put("byRepeat", byRepeat);
        report.put("rows", List.copyOf(observations));
        return report;
    }

    private static Map<String,Object> arms(List<Observation> rows, Map<String, RecipeEvaluationDataset.Example> examples,
                                           Double threshold) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("BASELINE", metrics(rows, examples, "BASELINE", null));
        result.put("RERANK_ONLY", metrics(rows, examples, "RERANK_ONLY", null));
        if (threshold != null) result.put("FILTERED", metrics(rows, examples, "FILTERED", threshold));
        return result;
    }

    private static Map<String,Object> metrics(List<Observation> rows, Map<String, RecipeEvaluationDataset.Example> examples,
                                               String arm, Double threshold) {
        List<Observation> positive = rows.stream().filter(row -> examples.get(row.queryId()).retrievalExpectedRelevant()).toList();
        List<Observation> empty = rows.stream().filter(row -> examples.get(row.queryId()).retrievalExpectedEmpty()).toList();
        List<Observation> near = empty.stream().filter(row -> "near_ood".equals(examples.get(row.queryId()).slice())).toList();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("queryObservations", rows.size());
        result.put("positiveObservations", positive.size());
        result.put("emptyExpectedObservations", empty.size());
        for (int k : List.of(1, 5, 10)) result.put("recallAt" + k, mean(positive, row ->
                RecipeRetrievalMetrics.recallAt(Set.copyOf(examples.get(row.queryId()).relevantRecipeIds()), ranked(row, arm, threshold), k)));
        result.put("mrrAt10", mean(positive, row -> RecipeRetrievalMetrics.reciprocalRankAt(
                Set.copyOf(examples.get(row.queryId()).relevantRecipeIds()), ranked(row, arm, threshold), 10)));
        result.put("ndcgAt10", mean(positive, row -> RecipeRetrievalMetrics.ndcgAt(
                examples.get(row.queryId()).grades(), ranked(row, arm, threshold), 10)));
        result.put("emptyExpectationAccuracy", mean(empty, row -> ranked(row, arm, threshold).isEmpty() ? 1 : 0));
        result.put("emptyFalseAcceptanceRate", mean(empty, row -> ranked(row, arm, threshold).isEmpty() ? 0 : 1));
        result.put("nearOodFalseAcceptanceRate", mean(near, row -> ranked(row, arm, threshold).isEmpty() ? 0 : 1));
        result.put("positiveFalseRejectionRate", mean(positive, row -> ranked(row, arm, threshold).isEmpty() ? 1 : 0));
        List<Observation> baselineRelevant = positive.stream().filter(row -> hasGold(
                ranked(row, "BASELINE", null), examples.get(row.queryId()))).toList();
        result.put("baselineRelevantEvidenceLossRate", mean(baselineRelevant, row -> hasGold(
                ranked(row, arm, threshold), examples.get(row.queryId())) ? 0 : 1));
        List<Long> latencies = rows.stream().map(row -> row.retrievalMs() + ("BASELINE".equals(arm) ? 0 : row.scoringMs())).toList();
        result.put("p50Ms", rows.isEmpty() ? null : RecipeEvaluationReport.percentile(latencies, 0.50));
        result.put("p95Ms", rows.isEmpty() ? null : RecipeEvaluationReport.percentile(latencies, 0.95));
        return result;
    }

    private static boolean hasGold(List<String> ids, RecipeEvaluationDataset.Example example) {
        return ids.stream().anyMatch(example.relevantRecipeIds()::contains);
    }

    static List<String> ranked(Observation row, String arm, Double threshold) {
        if ("BASELINE".equals(arm)) return row.candidates().stream().limit(10).toList();
        return row.scores().stream().filter(value -> threshold == null || value.relevanceScore() >= threshold)
                .limit(10).map(RecipeRerankResult.CandidateScore::recipeId).toList();
    }

    private static Double mean(List<Observation> rows, ToDoubleFunction<Observation> metric) {
        return rows.isEmpty() ? null : rows.stream().mapToDouble(metric).average().orElseThrow();
    }
}
