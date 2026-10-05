package com.diet.evaluation.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record RecipeEvaluationReport(Metadata metadata, List<ExperimentResult> experiments,
                              Map<String, Object> answerMetrics) {
    void write(Path target) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(target.toFile(), this);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to write recipe evaluation report", error);
        }
    }

    record Metadata(String corpusCommit, String corpusHash, int corpusDocumentCount,
                    String datasetVersion, String datasetHash,
                    int datasetQueryCount, int relevantQueryCount, int uniqueGoldCount,
                    int multiGoldQueryCount, int testRelevantQueryCount, int testUniqueGoldCount,
                    double maxSingleTestQueryImpactPercentage,
                    String applicationVersion, String embeddingModel, int dimensions) {
    }

    record ExperimentResult(RecipeExperiment experiment, int queryCount,
                            int relevantQueryCount, int emptyExpectedQueryCount,
                            double recallAt1, double recallAt5, double recallAt10,
                            double mrrAt10, double ndcgAt10, double parentRecall,
                            double duplicateRate, double emptyResultRate,
                            double emptyExpectationAccuracy, double nearOodFalsePositiveRate,
                            double p50LatencyMs, double p95LatencyMs,
                            int degradedQueries, Map<String, SliceMetrics> slices) {
    }

    record SliceMetrics(int queryCount, int relevantQueryCount, int emptyExpectedQueryCount,
                        double recallAt5, double mrrAt10, double emptyResultRate,
                        double emptyExpectationAccuracy) {
    }

    static double percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
