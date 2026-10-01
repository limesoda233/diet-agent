package com.diet.service.recipe.rag.model;

public record RecipeChunk(
        String chunkId,
        String parentRecipeId,
        ChunkType type,
        int order,
        String text,
        String contentHash,
        String sourcePath) {

    public RecipeChunk(String chunkId, String parentRecipeId, ChunkType type,
                       int order, String text, String contentHash) {
        this(chunkId, parentRecipeId, type, order, text, contentHash, "");
    }

    public enum ChunkType {
        INTRODUCTION,
        INGREDIENTS,
        CALCULATION,
        STEPS,
        ADDITIONAL
    }
}
