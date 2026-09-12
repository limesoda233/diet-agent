package com.diet.service.cooking;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@Data
@ConfigurationProperties("diet.cooking")
public class CookingProperties {
    private boolean enabled;
    private Duration timeout=Duration.ofSeconds(3);
    private Duration initializationTimeout=Duration.ofSeconds(20);
    private String serverJar="";
}
