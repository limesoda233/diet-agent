package com.diet.service.video;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VideoMcpProperties.class)
public class VideoMcpConfiguration {
    @Bean
    CookingVideoSearchProvider cookingVideoSearchProvider(VideoMcpProperties properties) {
        if (properties.isEnabled() && "fake".equalsIgnoreCase(properties.getProvider())) {
            return new FakeCookingVideoSearchProvider();
        }
        return new DisabledCookingVideoSearchProvider();
    }

    @Bean
    CookingVideoSearchService cookingVideoSearchService(CookingVideoSearchProvider provider,
                                                        VideoMcpProperties properties) {
        return new CookingVideoSearchService(provider, properties);
    }
}
