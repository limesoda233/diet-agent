package com.diet.service.video;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VideoMcpProperties.class)
public class VideoMcpConfiguration {
    @Bean(destroyMethod = "close")
    AgentScopeBilibiliMcpClient bilibiliMcpClient(VideoMcpProperties properties) {
        return new AgentScopeBilibiliMcpClient(properties);
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
