package com.diet.evaluation.recipe;

import java.util.List;
import java.util.regex.Pattern;

final class RecipeAnswerMetrics {
    private static final Pattern CITATION = Pattern.compile("\\[(?:S|P)\\d+]");

    private RecipeAnswerMetrics() {
    }

    static double citationCoverage(List<String> requiredFacts, String answer) {
        if (requiredFacts == null || requiredFacts.isEmpty()) return 1;
        String safeAnswer = answer == null ? "" : answer;
        long covered = requiredFacts.stream().filter(fact -> citedFact(safeAnswer, fact)).count();
        return (double) covered / requiredFacts.size();
    }

    static double supportedFactRate(List<String> assertedFacts, List<String> evidenceFacts) {
        if (assertedFacts == null || assertedFacts.isEmpty()) return 1;
        List<String> evidence = evidenceFacts == null ? List.of() : evidenceFacts;
        long supported = assertedFacts.stream().filter(asserted -> evidence.stream()
                .anyMatch(fact -> normalize(fact).contains(normalize(asserted))
                        || normalize(asserted).contains(normalize(fact)))).count();
        return (double) supported / assertedFacts.size();
    }

    static double refusalAccuracy(List<Boolean> expected, List<Boolean> actual) {
        if (expected == null || actual == null || expected.isEmpty() || expected.size() != actual.size()) return 0;
        int correct = 0;
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index).equals(actual.get(index))) correct++;
        }
        return (double) correct / expected.size();
    }

    private static boolean citedFact(String answer, String fact) {
        for (String segment : answer.split("[。！？\\n]")) {
            if (segment.contains(fact) && CITATION.matcher(segment).find()) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }
}
