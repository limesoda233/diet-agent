package com.diet.service.video;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BilibiliMcpCookingVideoSearchProviderTest {

    @Test
    void mapsPinnedMcpTextResultToCookingVideos() {
        String response = """
                Search results for "宫保鸡丁 做法 教程":
                1. "<em class=\"keyword\">宫保鸡丁</em>家常做法" - 小高姐
                 BV ID: BV1ab411c7mD
                 Views: 12,345
                 Danmaku: 80
                 Likes: 900
                 Duration: 05:32
                 Published: 2026-01-01
                 Description: 家常做法

                2. "低油宫保鸡丁教程" - 厨房阿姨
                 BV ID: BV9xy4y1z7kP
                 Views: 9,876
                 Danmaku: 20
                 Likes: 500
                 Duration: 1:02:03
                 Published: 2026-01-02
                 Description: 低油版本

                Found 100 related videos in total, currently showing 2 results from page 1.
                """;
        BilibiliVideoMcpGateway gateway = (keyword, count) -> Mono.just(response);
        var provider = new BilibiliMcpCookingVideoSearchProvider(gateway, " 做法 教程");

        var videos = provider.search("宫保鸡丁", VideoPlatform.ANY, 2).block(Duration.ofSeconds(1));

        assertThat(videos).hasSize(2);
        assertThat(videos.get(0)).satisfies(video -> {
            assertThat(video.query()).isEqualTo("宫保鸡丁");
            assertThat(video.title()).isEqualTo("宫保鸡丁家常做法");
            assertThat(video.creator()).isEqualTo("小高姐");
            assertThat(video.url()).isEqualTo("https://www.bilibili.com/video/BV1ab411c7mD");
            assertThat(video.durationSeconds()).isEqualTo(332);
            assertThat(video.platform()).isEqualTo(VideoPlatform.BILIBILI);
            assertThat(video.source()).isEqualTo("mcp:bilibili:search_videos@0.0.7");
        });
        assertThat(videos.get(1).durationSeconds()).isEqualTo(3723);
    }

    @Test
    void rejectsDouyinInsteadOfSilentlySearchingAnotherPlatform() {
        var provider = new BilibiliMcpCookingVideoSearchProvider((keyword, count) -> Mono.just(""), " 做法 教程");

        assertThatThrownBy(() -> provider.search("宫保鸡丁", VideoPlatform.DOUYIN, 3).block())
                .hasMessageContaining("douyin_video_search_not_configured");
    }

    @Test
    void rejectsUnexpectedSuccessfulPayloadInsteadOfInventingResults() {
        var provider = new BilibiliMcpCookingVideoSearchProvider(
                (keyword, count) -> Mono.just("upstream format changed"), " 做法 教程");

        assertThatThrownBy(() -> provider.search("宫保鸡丁", VideoPlatform.BILIBILI, 3).block())
                .hasMessageContaining("bilibili_mcp_unrecognized_response");
    }
}
