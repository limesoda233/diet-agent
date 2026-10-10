package com.diet.service.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliMcpLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "diet.video-mcp.live", matches = "true")
    void searchesWithProductionDeadlineAfterWarmup() {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        properties.setProvider("bilibili-mcp");
        var client = new AgentScopeBilibiliMcpClient(properties);
        try {
            client.warmUp().block(Duration.ofSeconds(35));
            var provider = new BilibiliMcpCookingVideoSearchProvider(client, properties.getQuerySuffix());
            var service = new CookingVideoSearchService(provider, properties);
            var videos = service.search("番茄炒蛋", VideoPlatform.BILIBILI, 2)
                    .block(Duration.ofSeconds(5));

            assertThat(videos).isNotEmpty().hasSizeLessThanOrEqualTo(2);
            assertThat(videos.get(0).url()).startsWith("https://www.bilibili.com/video/BV");
        } finally {
            client.close();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "diet.video-mcp.live", matches = "true")
    void searchesPublicCookingVideosThroughRealStdioMcp() {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        properties.setProvider("bilibili-mcp");
        properties.setTimeout(Duration.ofSeconds(60));
        properties.setInitializationTimeout(Duration.ofSeconds(60));
        var client = new AgentScopeBilibiliMcpClient(properties);
        try {
            var provider = new BilibiliMcpCookingVideoSearchProvider(client, properties.getQuerySuffix());
            var videos = provider.search("番茄炒蛋", VideoPlatform.BILIBILI, 2)
                    .block(Duration.ofSeconds(90));

            assertThat(videos).isNotEmpty().hasSizeLessThanOrEqualTo(2).allSatisfy(video -> {
                assertThat(video.platform()).isEqualTo(VideoPlatform.BILIBILI);
                assertThat(video.url()).startsWith("https://www.bilibili.com/video/BV");
                assertThat(video.title()).isNotBlank();
            });
        } finally {
            client.close();
        }
    }
}
