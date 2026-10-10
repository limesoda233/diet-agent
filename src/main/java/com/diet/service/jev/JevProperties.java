package com.diet.service.jev;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties("diet.jev")
public class JevProperties {
    private boolean enabled = true;
    private boolean intentEnabled = true;
    private boolean evaluationEnabled = true;
    private String endpoint = "https://api.typesafe.ai/v1/systemone";
    @ToString.Exclude
    private String apiKey = "";
    private String model = "jev-latest";
    private Duration timeout = Duration.ofSeconds(3);
    private double intentConfidenceThreshold = 0.70;
    private double slotProbabilityThreshold = 0.70;

    public boolean configured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
