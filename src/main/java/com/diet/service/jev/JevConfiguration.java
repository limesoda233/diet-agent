package com.diet.service.jev;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(JevProperties.class)
public class JevConfiguration {
    @Bean
    public JevClient jevClient(JevProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        return new HttpJevClient(properties, httpClient);
    }

    @Bean
    public JevIntentRecognitionService jevIntentRecognitionService(JevClient client, JevProperties properties) {
        return new JevIntentRecognitionService(client, properties);
    }

    @Bean
    public JevEvaluationJudge jevEvaluationJudge(JevClient client, JevProperties properties) {
        return new JevEvaluationJudge(client, properties);
    }
}
