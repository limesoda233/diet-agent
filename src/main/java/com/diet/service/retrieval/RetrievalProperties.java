package com.diet.service.retrieval;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties("diet.retrieval")
public class RetrievalProperties {
    public enum Strategy { LEGACY, STRUCTURED, HYBRID }
    private Strategy strategy = Strategy.LEGACY;
    private boolean enabled;
    private boolean indexWorkerEnabled;
    private String indexCommand = "";
    private String importFile = "";
    private Embedding embedding = new Embedding();
    private Qdrant qdrant = new Qdrant();

    @Data public static class Embedding {
        private String endpoint = "";
        private String model = "";
        @ToString.Exclude private String apiKey = "";
        private int dimensions;
        private Duration timeout = Duration.ofSeconds(8);
        private String documentPrefix = "";
        private String queryPrefix = "";
        public boolean configured() { return !endpoint.isBlank() && !model.isBlank() && !apiKey.isBlank() && dimensions > 0; }
    }
    @Data public static class Qdrant {
        private String endpoint = "http://127.0.0.1:6333";
        @ToString.Exclude private String apiKey = "";
        private String collection = "diet_meals_unconfigured";
        private Duration timeout = Duration.ofSeconds(2);
    }
}
