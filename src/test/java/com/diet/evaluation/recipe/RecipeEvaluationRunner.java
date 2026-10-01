package com.diet.evaluation.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecipeEvaluationRunner {
    RecipeEvaluationReport.ExperimentResult run(RecipeExperiment experiment,
                                                List<RecipeEvaluationDataset.Example> examples,
                                                RetrievalCall retrieval) {
        List<QueryResult> results = new ArrayList<>();
        for (RecipeEvaluationDataset.Example example : examples) {
            results.add(retrieval.retrieve(experiment, example));
        }
        List<QueryResult> judged = results.stream().filter(result -> !result.example().mustRefuse()).toList();
        Map<String, RecipeEvaluationReport.SliceMetrics> slices = new LinkedHashMap<>();
        examples.stream().map(RecipeEvaluationDataset.Example::slice).distinct().forEach(slice -> {
            List<QueryResult> values = results.stream().filter(result -> result.example().slice().equals(slice)).toList();
            List<QueryResult> relevant = values.stream().filter(result -> !result.example().mustRefuse()).toList();
            slices.put(slice, new RecipeEvaluationReport.SliceMetrics(values.size(),
                    average(relevant, result -> recall(result, 5)),
                    average(relevant, RecipeEvaluationRunner::mrr),
                    values.isEmpty() ? 0 : (double) values.stream().filter(result -> result.rankedParentIds().isEmpty()).count() / values.size()));
        });
        List<Long> latencies = results.stream().map(QueryResult::latencyMs).toList();
        return new RecipeEvaluationReport.ExperimentResult(experiment, results.size(),
                average(judged, result -> recall(result, 1)),
                average(judged, result -> recall(result, 5)),
                average(judged, result -> recall(result, 10)),
                average(judged, RecipeEvaluationRunner::mrr),
                average(judged, result -> RecipeRetrievalMetrics.ndcgAt(
                        result.example().grades(), result.rankedParentIds(), 10)),
                average(judged, result -> recall(result, 10)),
                average(results, result -> RecipeRetrievalMetrics.duplicateRate(result.rankedParentIds())),
                results.isEmpty() ? 0 : (double) results.stream().filter(result -> result.rankedParentIds().isEmpty()).count() / results.size(),
                RecipeEvaluationReport.percentile(latencies, 0.50),
                RecipeEvaluationReport.percentile(latencies, 0.95),
                (int) results.stream().filter(QueryResult::degraded).count(), Map.copyOf(slices));
    }

    private static double recall(QueryResult result, int k) {
        return RecipeRetrievalMetrics.recallAt(Set.copyOf(result.example().relevantRecipeIds()),
                result.rankedParentIds(), k);
    }

    private static double mrr(QueryResult result) {
        return RecipeRetrievalMetrics.reciprocalRankAt(Set.copyOf(result.example().relevantRecipeIds()),
                result.rankedParentIds(), 10);
    }

    private static double average(List<QueryResult> results, Metric metric) {
        return results.isEmpty() ? 0 : results.stream().mapToDouble(metric::value).average().orElse(0);
    }

    @FunctionalInterface
    interface RetrievalCall {
        QueryResult retrieve(RecipeExperiment experiment, RecipeEvaluationDataset.Example example);
    }

    @FunctionalInterface
    private interface Metric {
        double value(QueryResult result);
    }

    record QueryResult(RecipeEvaluationDataset.Example example, List<String> rankedParentIds,
                       long latencyMs, boolean degraded) {
        QueryResult {
            rankedParentIds = rankedParentIds == null ? List.of() : List.copyOf(rankedParentIds);
        }
    }
}
