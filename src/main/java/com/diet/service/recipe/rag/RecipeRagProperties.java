package com.diet.service.recipe.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("diet.recipe-rag")
public class RecipeRagProperties {
    private boolean enabled;
    private final Embedding embedding = new Embedding();
    private final Qdrant qdrant = new Qdrant();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Embedding getEmbedding() { return embedding; }
    public Qdrant getQdrant() { return qdrant; }

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
}
