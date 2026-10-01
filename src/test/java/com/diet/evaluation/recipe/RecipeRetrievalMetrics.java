package com.diet.evaluation.recipe;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecipeRetrievalMetrics {
    private RecipeRetrievalMetrics() {
    }

    static double recallAt(Set<String> relevant, List<String> ranked, int k) {
        if (relevant == null || relevant.isEmpty()) return 0;
        Set<String> found = new HashSet<>(ranked.subList(0, Math.min(Math.max(k, 0), ranked.size())));
        found.retainAll(relevant);
        return (double) found.size() / relevant.size();
    }

    static double reciprocalRankAt(Set<String> relevant, List<String> ranked, int k) {
        if (relevant == null || relevant.isEmpty()) return 0;
        for (int index = 0; index < Math.min(Math.max(k, 0), ranked.size()); index++) {
            if (relevant.contains(ranked.get(index))) return 1.0 / (index + 1);
        }
        return 0;
    }

    static double ndcgAt(Map<String, Integer> grades, List<String> ranked, int k) {
        if (grades == null || grades.isEmpty()) return 0;
        int limit = Math.min(Math.max(k, 0), ranked.size());
        double dcg = 0;
        for (int index = 0; index < limit; index++) {
            dcg += gain(grades.getOrDefault(ranked.get(index), 0)) / log2(index + 2);
        }
        List<Integer> ideal = grades.values().stream().sorted(java.util.Comparator.reverseOrder()).toList();
        double idcg = 0;
        for (int index = 0; index < Math.min(k, ideal.size()); index++) {
            idcg += gain(ideal.get(index)) / log2(index + 2);
        }
        return idcg == 0 ? 0 : dcg / idcg;
    }

    static double duplicateRate(List<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) return 0;
        return (double) (parentIds.size() - new HashSet<>(parentIds).size()) / parentIds.size();
    }

    static double emptyResultRate(List<? extends List<?>> results) {
        if (results == null || results.isEmpty()) return 0;
        return (double) results.stream().filter(List::isEmpty).count() / results.size();
    }

    private static double gain(int grade) { return Math.pow(2, grade) - 1; }
    private static double log2(int value) { return Math.log(value) / Math.log(2); }
}
