package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeHit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion：只使用名次，避免直接相加 BM25 与 cosine 的不同量纲分数。
 * 同一菜谱可命中多个子块，每个分支只保留它排名最高的一个，避免长文档靠块数刷分。
 * 分数为两支 1/(常数 + 名次) 的和；未命中某支时该支贡献为 0。
 * RRF 分数是排序依据，不是“相关概率”，不能直接当作置信度阈值。
 */
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

            double sparseScore = reciprocal(sparseHit, rankConstant);
            double denseScore = reciprocal(denseHit, rankConstant);
            double score = sparseScore + denseScore;

            RecipeHit representative = chooseRepresentative(sparseHit, denseHit);

            System.out.printf(
                    "title=%s parent=%s sparseRank=%s denseRank=%s sparseRrf=%.5f denseRrf=%.5f total=%.5f%n",
                    representative.title(),
                    parentId,
                    sparseHit == null ? "-" : sparseHit.rank(),
                    denseHit == null ? "-" : denseHit.rank(),
                    sparseScore,
                    denseScore,
                    score
            );

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

    /** 按原始 rank 去重；相同排名用稳定 chunk ID 打破平局，保证可复现。 */
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
