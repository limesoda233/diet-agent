package com.diet.service.video;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VideoMcpProperties.class)
public class VideoMcpConfiguration {
    private static final Logger log = LoggerFactory.getLogger(VideoMcpConfiguration.class);
    @Bean(destroyMethod = "close")
    AgentScopeBilibiliMcpClient bilibiliMcpClient(VideoMcpProperties properties) {
        return new AgentScopeBilibiliMcpClient(properties);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> bilibiliMcpWarmup(VideoMcpProperties properties,
                                                                AgentScopeBilibiliMcpClient client) {
        return event -> {
            if (!properties.isEnabled() || !"bilibili-mcp".equalsIgnoreCase(properties.getProvider())) return;
            client.warmUp().subscribe(ignored -> { },
                    error -> log.warn("Bilibili MCP warm-up unavailable: {}", error.getClass().getSimpleName()));
        };
    }

    @Bean
    CookingVideoSearchProvider cookingVideoSearchProvider(VideoMcpProperties properties,
                                                           AgentScopeBilibiliMcpClient bilibiliMcpClient) {
        if (properties.isEnabled() && "fake".equalsIgnoreCase(properties.getProvider())) {
            return new FakeCookingVideoSearchProvider();
        }
        if (properties.isEnabled() && "bilibili-mcp".equalsIgnoreCase(properties.getProvider())) {
            return new BilibiliMcpCookingVideoSearchProvider(bilibiliMcpClient, properties.getQuerySuffix());
        }
        return new DisabledCookingVideoSearchProvider();
    }

    @Bean
    CookingVideoSearchService cookingVideoSearchService(CookingVideoSearchProvider provider,
                                                        VideoMcpProperties properties) {
        return new CookingVideoSearchService(provider, properties);
    }
}
