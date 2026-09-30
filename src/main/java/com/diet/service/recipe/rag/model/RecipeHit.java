package com.diet.service.recipe.rag.model;

public record RecipeHit(
        String chunkId,
        String parentRecipeId,
        String title,
        RecipeChunk.ChunkType chunkType,
        String text,
        double score,
        int rank,
        String channel) {

    public RecipeHit withRank(int newRank) {
        return new RecipeHit(chunkId, parentRecipeId, title, chunkType, text, score, newRank, channel);
    }
}
