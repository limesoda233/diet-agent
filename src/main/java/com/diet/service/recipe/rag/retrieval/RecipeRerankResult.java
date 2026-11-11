package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RetrievedRecipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scores are diagnostics, never replacements for the original RRF scores or calibrated probabilities. */
public record RecipeRerankResult(Status status, List<RetrievedRecipe> recipes,
                                 List<CandidateScore> candidates, String model, Double minScore,
                                 int inputCount, long durationMs, String errorCode,
                                 String queryHash, List<String> documentHashes) {
    public enum Status { BYPASSED, ACCEPTED, FILTERED, UNAVAILABLE }
    public record CandidateScore(String recipeId, double rrfScore, double relevanceScore) { }

    public RecipeRerankResult {
        recipes = List.copyOf(recipes);
        candidates = List.copyOf(candidates);
        documentHashes = List.copyOf(documentHashes);
    }

    public static RecipeRerankResult bypassed(List<RetrievedRecipe> recipes, int inputCount) {
        return new RecipeRerankResult(Status.BYPASSED, recipes, List.of(), "identity", null,
                inputCount, 0, "", "", List.of());
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status.name());
        result.put("model", model);
        if (minScore != null) result.put("minScore", minScore);
        result.put("inputCount", inputCount);
        result.put("acceptedCount", recipes.size());
        result.put("durationMs", durationMs);
        result.put("errorCode", errorCode);
        result.put("candidates", candidates);
        result.put("queryHash", queryHash);
        result.put("documentHashes", documentHashes);
        return Map.copyOf(result);
    }
}
