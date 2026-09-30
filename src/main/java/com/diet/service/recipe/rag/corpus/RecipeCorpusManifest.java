package com.diet.service.recipe.rag.corpus;

import java.util.List;

public record RecipeCorpusManifest(
        String corpusName,
        String sourceCommit,
        int documentCount,
        int chunkCount,
        String corpusHash,
        List<Entry> entries) {

    public RecipeCorpusManifest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public record Entry(String relativePath, String recipeId, String contentHash, int chunkCount) {
    }
}
