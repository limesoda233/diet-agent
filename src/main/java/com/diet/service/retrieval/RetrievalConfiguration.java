package com.diet.service.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalConfiguration {
    @Bean public org.springframework.boot.ApplicationRunner retrievalStartupCheck(RetrievalProperties properties,org.springframework.jdbc.core.JdbcTemplate jdbc,VectorStore vector) {
        return args -> {
            if(!properties.getIndexCommand().isBlank() || !properties.isEnabled()) return;
            jdbc.queryForList("SELECT meal_id FROM meal_knowledge LIMIT 0");
            jdbc.queryForList("SELECT meal_id,revision FROM meal_index_task LIMIT 0");
            jdbc.queryForList("SELECT collection_name,meal_id FROM meal_index_progress LIMIT 0");
            if(properties.getStrategy()==RetrievalProperties.Strategy.HYBRID && !properties.getEmbedding().configured())
                org.slf4j.LoggerFactory.getLogger(RetrievalConfiguration.class).warn("Hybrid retrieval has no embedding configuration; requests will degrade to STRUCTURED");
            else if(properties.getStrategy()==RetrievalProperties.Strategy.HYBRID) {
                try {
                    if(!vector.isReady()) org.slf4j.LoggerFactory.getLogger(RetrievalConfiguration.class).warn("Qdrant index is not ready; requests will degrade to STRUCTURED");
                } catch(RetrievalDependencyException failure) {
                    org.slf4j.LoggerFactory.getLogger(RetrievalConfiguration.class).warn("Qdrant startup check failed: {}; requests will degrade to STRUCTURED",failure.getMessage());
                }
            }
        };
    }
    @Bean public EmbeddingClient embeddingClient(RetrievalProperties properties) {
        return new HttpEmbeddingClient(properties.getEmbedding(),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }
    @Bean public VectorStore vectorStore(RetrievalProperties properties) {
        return new QdrantVectorStore(properties.getQdrant(),properties.getEmbedding().getDimensions(),properties.getEmbedding().getModel(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }
}
