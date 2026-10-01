package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;

public enum RecipeExperiment {
    BM25(RecipeRetrievalMode.SPARSE_ONLY, false, false, false),
    DENSE(RecipeRetrievalMode.DENSE_ONLY, false, false, false),
    SERIAL_HYBRID(RecipeRetrievalMode.SERIAL_HYBRID, false, false, false),
    PARALLEL_HYBRID(RecipeRetrievalMode.PARALLEL_HYBRID, false, false, false),
    PARALLEL_PARENT(RecipeRetrievalMode.PARALLEL_HYBRID, true, false, false),
    PARALLEL_PARENT_RERANK(RecipeRetrievalMode.PARALLEL_HYBRID, true, true, false),
    PARALLEL_PARENT_REWRITE(RecipeRetrievalMode.PARALLEL_HYBRID, true, false, true);

    private final RecipeRetrievalMode mode;
    private final boolean parentExpansion;
    private final boolean rerank;
    private final boolean rewrite;

    RecipeExperiment(RecipeRetrievalMode mode, boolean parentExpansion, boolean rerank, boolean rewrite) {
        this.mode = mode;
        this.parentExpansion = parentExpansion;
        this.rerank = rerank;
        this.rewrite = rewrite;
    }

    public RecipeRetrievalMode mode() { return mode; }
    public boolean parentExpansion() { return parentExpansion; }
    public boolean rerank() { return rerank; }
    public boolean rewrite() { return rewrite; }
}
