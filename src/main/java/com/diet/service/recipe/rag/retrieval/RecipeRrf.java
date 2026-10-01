package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RecipeRrf {
    private RecipeRrf() {
    }

    public static List<RecipeHit> fuse(List<RecipeHit> sparse, List<RecipeHit> dense, int rankConstant) {
        if (rankConstant < 0) throw new IllegalArgumentException("rankConstant must be non-negative");
        List<RecipeHit> safeSparse = sparse == null ? List.of() : sparse;
        List<RecipeHit> safeDense = dense == null ? List.of() : dense;
        Map<String, RecipeHit> sparseParents = bestPerParent(safeSparse);
        Map<String, RecipeHit> denseParents = bestPerParent(safeDense);
        Set<String> parentIds = new LinkedHashSet<>();
        parentIds.addAll(sparseParents.keySet());
        parentIds.addAll(denseParents.keySet());

        List<ScoredHit> scored = new ArrayList<>();
        for (String parentId : parentIds) {
            RecipeHit sparseHit = sparseParents.get(parentId);
            RecipeHit denseHit = denseParents.get(parentId);
            double score = reciprocal(sparseHit, rankConstant) + reciprocal(denseHit, rankConstant);
            RecipeHit representative = chooseRepresentative(sparseHit, denseHit);
            scored.add(new ScoredHit(representative, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredHit::score).reversed()
                .thenComparing(value -> value.hit().parentRecipeId())
                .thenComparing(value -> value.hit().chunkId()));

        List<RecipeHit> result = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            ScoredHit value = scored.get(index);
            RecipeHit hit = value.hit();
            result.add(new RecipeHit(hit.chunkId(), hit.parentRecipeId(), hit.title(), hit.chunkType(),
                    hit.text(), value.score(), index + 1, "RRF"));
        }
        return List.copyOf(result);
    }

    private static Map<String, RecipeHit> bestPerParent(List<RecipeHit> hits) {
        Map<String, RecipeHit> result = new LinkedHashMap<>();
        hits.stream().sorted(Comparator.comparingInt(RecipeHit::rank).thenComparing(RecipeHit::chunkId))
                .forEach(hit -> result.putIfAbsent(hit.parentRecipeId(), hit));
        return result;
    }

    private static double reciprocal(RecipeHit hit, int rankConstant) {
        return hit == null ? 0.0 : 1.0 / (rankConstant + Math.max(1, hit.rank()));
    }

    private static RecipeHit chooseRepresentative(RecipeHit sparse, RecipeHit dense) {
        if (sparse == null) return dense;
        if (dense == null) return sparse;
        return Comparator.comparingInt(RecipeHit::rank).thenComparing(RecipeHit::channel)
                .thenComparing(RecipeHit::chunkId).compare(sparse, dense) <= 0 ? sparse : dense;
    }

    private record ScoredHit(RecipeHit hit, double score) {
    }
}
