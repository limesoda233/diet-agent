package com.diet.service.video;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CookingVideoSearchServiceTest {

    @Test
    void disabledProviderFailsFastWithoutPretendingToHavePlatformResults() {
        var properties = new VideoMcpProperties();
        var service = new CookingVideoSearchService(new DisabledCookingVideoSearchProvider(), properties);

        assertThatThrownBy(() -> service.search("宫保鸡丁", VideoPlatform.ANY, 3).block(Duration.ofSeconds(1)))
                .hasMessageContaining("video_search_disabled");
    }

    @Test
    void fakeProviderSupportsTheFutureMcpContractDeterministically() {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), properties);

        var videos = service.search("宫保鸡丁", VideoPlatform.BILIBILI, 2).block(Duration.ofSeconds(1));

        assertThat(videos).hasSize(2).allSatisfy(video -> {
            assertThat(video.platform()).isEqualTo(VideoPlatform.BILIBILI);
            assertThat(video.url()).startsWith("https://example.invalid/");
            assertThat(video.query()).isEqualTo("宫保鸡丁");
        });
        assertThat(CookingVideoSearchService.TOOL_NAME).isEqualTo("search_cooking_videos");
    }

    @Test
    void routesOnlyExplicitVideoRequestsAndValidatesStructuredArguments() {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), properties);

        assertThat(service.matches("推荐一顿清淡午餐", Map.of())).isFalse();
        assertThat(service.matches("宫保鸡丁怎么做", Map.of())).isFalse();
        assertThat(service.matches("找宫保鸡丁的哔哩哔哩教学视频", Map.of())).isTrue();
        assertThat(service.route("找宫保鸡丁教学视频", Map.of()).platform()).isEqualTo(VideoPlatform.ANY);
        assertThat(service.route("视频", Map.of("videoSearch", Map.of(
                "query", "宫保鸡丁", "platform", "DOUYIN", "limit", 2))).limit()).isEqualTo(2);
        assertThatThrownBy(() -> service.route("视频", Map.of("videoSearch", Map.of(
                "query", "宫保鸡丁", "limit", 11)))).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.route("视频", Map.of("videoSearch", Map.of(
                "query", "宫保鸡丁", "secret", "x")))).hasMessageContaining("secret");
    }
}
