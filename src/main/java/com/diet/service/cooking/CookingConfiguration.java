package com.diet.service.cooking;
import com.diet.service.trace.AgentTraceService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(CookingProperties.class)
public class CookingConfiguration {
    @Bean(destroyMethod="close") public RecipeMcpClient recipeMcpClient(CookingProperties properties) { return new RecipeMcpClient(properties); }
    @Bean public CookingService cookingService(RecipeMcpClient client,CookingProperties properties,AgentTraceService trace) { return new CookingService(client,properties,trace); }
}
