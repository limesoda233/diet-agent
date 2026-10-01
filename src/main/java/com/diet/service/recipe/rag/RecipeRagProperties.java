package com.diet.service.recipe.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("diet.recipe-rag")
public class RecipeRagProperties {
    private boolean enabled;
    private String sourceCommit = "a0b14bd388ee996943173679c3a7c9db7da04a6c";
    private String corpusPath = "data/HowToCook";
    private String luceneDirectory = "data/recipe-rag/lucene";
    private final Embedding embedding = new Embedding();
    private final Qdrant qdrant = new Qdrant();
    private final Retrieval retrieval = new Retrieval();
    private final OptionalStage queryRewrite = new OptionalStage();
    private final OptionalStage rerank = new OptionalStage();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSourceCommit() { return sourceCommit; }
    public void setSourceCommit(String sourceCommit) { this.sourceCommit = sourceCommit; }
    public String getCorpusPath() { return corpusPath; }
    public void setCorpusPath(String corpusPath) { this.corpusPath = corpusPath; }
    public String getLuceneDirectory() { return luceneDirectory; }
    public void setLuceneDirectory(String luceneDirectory) { this.luceneDirectory = luceneDirectory; }
    public Embedding getEmbedding() { return embedding; }
    public Qdrant getQdrant() { return qdrant; }
    public Retrieval getRetrieval() { return retrieval; }
    public OptionalStage getQueryRewrite() { return queryRewrite; }
    public OptionalStage getRerank() { return rerank; }

    public static class Embedding {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "bge-m3:567m";
        private int dimensions = 1024;
        private Duration timeout = Duration.ofSeconds(3);
        private int maxAttempts = 1;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class Qdrant {
        private String baseUrl = "http://localhost:6333";
        private String collection = "diet_recipe_chunks_bge_m3_1024";
        private String apiKey = "";
        private Duration timeout = Duration.ofSeconds(3);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Retrieval {
        private String mode = "parallel_hybrid";
        private int sparseTopK = 20;
        private int denseTopK = 20;
        private int finalTopK = 5;
        private int rrfRankConstant = 60;
        private Duration branchTimeout = Duration.ofSeconds(2);

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getSparseTopK() { return sparseTopK; }
        public void setSparseTopK(int sparseTopK) { this.sparseTopK = sparseTopK; }
        public int getDenseTopK() { return denseTopK; }
        public void setDenseTopK(int denseTopK) { this.denseTopK = denseTopK; }
        public int getFinalTopK() { return finalTopK; }
        public void setFinalTopK(int finalTopK) { this.finalTopK = finalTopK; }
        public int getRrfRankConstant() { return rrfRankConstant; }
        public void setRrfRankConstant(int rrfRankConstant) { this.rrfRankConstant = rrfRankConstant; }
        public Duration getBranchTimeout() { return branchTimeout; }
        public void setBranchTimeout(Duration branchTimeout) { this.branchTimeout = branchTimeout; }
    }

    public static class OptionalStage {
        private boolean enabled;
        private Duration timeout = Duration.ofSeconds(2);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }
}
