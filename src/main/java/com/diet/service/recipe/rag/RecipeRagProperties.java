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
    private final Rerank rerank = new Rerank();

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
    public Rerank getRerank() { return rerank; }

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
        private String baseUrl = "http://localhost:16333";
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

    /** Explicit opt-in. A threshold is model-specific and must never be silently guessed. */
    public static class Rerank extends OptionalStage {
        private String endpoint = "https://api.siliconflow.cn/v1/rerank";
        private String apiKey = "";
        private String model = "BAAI/bge-reranker-v2-m3";
        private int candidateTopK = 20;
        private int topN = 3;
        private int maxDocumentChars = 3000;
        private boolean filterEnabled = true;
        private Double minScore;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getCandidateTopK() { return candidateTopK; }
        public void setCandidateTopK(int candidateTopK) { this.candidateTopK = candidateTopK; }
        public int getTopN() { return topN; }
        public void setTopN(int topN) { this.topN = topN; }
        public int getMaxDocumentChars() { return maxDocumentChars; }
        public void setMaxDocumentChars(int maxDocumentChars) { this.maxDocumentChars = maxDocumentChars; }
        public boolean isFilterEnabled() { return filterEnabled; }
        public void setFilterEnabled(boolean filterEnabled) { this.filterEnabled = filterEnabled; }
        public Double getMinScore() { return minScore; }
        public void setMinScore(Double minScore) { this.minScore = minScore; }

        public void validate() {
            // Never include supplied values in configuration errors: endpoint/key may contain secrets.
            try {
                var uri = java.net.URI.create(endpoint);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getRawQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("rerank endpoint must be an HTTP(S) URL without credentials/query/fragment");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.contains("\n") || apiKey.contains("\r"))
                throw new IllegalArgumentException("rerank api-key is required");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("rerank model is required");
            if (getTimeout() == null || getTimeout().isZero() || getTimeout().isNegative())
                throw new IllegalArgumentException("rerank timeout must be positive");
            if (candidateTopK < 1 || candidateTopK > 50 || topN < 1 || topN > candidateTopK
                    || maxDocumentChars < 512 || maxDocumentChars > 8000)
                throw new IllegalArgumentException("rerank candidate/top-n/document budgets are invalid");
            if (minScore != null && !Double.isFinite(minScore))
                throw new IllegalArgumentException("rerank min-score must be finite");
            if (filterEnabled && minScore == null)
                throw new IllegalArgumentException("rerank filtering requires explicit min-score calibrated on dev");
        }
    }
}
