package com.diet.service.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CookingVideoSearchServiceTest {

    @ParameterizedTest
    @ValueSource(strings = {"不要辣的菜谱", "只要清淡菜谱", "不用放辣椒的做法", "不要太咸，给我菜谱"})
    void recipeConstraintsWithoutVideoRequestNeverSelectPureVideo(String question) {
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), new VideoMcpProperties());

        assertThat(service.isVideoOnly(question, Map.of())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"只看宫保鸡丁视频", "不要菜谱，想看宫保鸡丁视频", "不用文字，给我宫保鸡丁视频教程"})
    void explicitVideoRequestsStillSelectPureVideo(String question) {
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), new VideoMcpProperties());

        assertThat(service.isVideoOnly(question, Map.of())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"宫保鸡丁不要辣的菜谱，有视频吗", "只要清淡菜谱，有视频吗"})
    void recipeConstraintsWithOptionalVideoRemainMixedRequests(String question) {
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), new VideoMcpProperties());

        assertThat(service.isVideoOnly(question, Map.of())).isFalse();
    }

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
        assertThat(service.format(videos))
                .isEqualTo("找到 2 个做饭教学视频，点击卡片即可观看。")
                .doesNotContain("https://");
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

    @Test
    void distinguishesVideoOnlyFromRecipeQuestionsThatAlsoMentionVideo() {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), properties);

        assertThat(service.isVideoOnly("只要宫保鸡丁教学视频", Map.of())).isTrue();
        assertThat(service.isVideoOnly("找一个宫保鸡丁教学视频", Map.of())).isTrue();
        assertThat(service.isVideoOnly("找一个宫保鸡丁视频教程", Map.of())).isTrue();
        assertThat(service.isVideoOnly("宫保鸡丁怎么做，有视频吗", Map.of())).isFalse();
        assertThat(service.isVideoOnly("宫保鸡丁怎么做，想看视频教程", Map.of())).isFalse();
        assertThat(service.isVideoOnly("宫保鸡丁咋做啊", Map.of())).isFalse();
        assertThat(service.isVideoOnly("宫保鸡丁需要什么食材，有视频吗", Map.of())).isFalse();
        assertThat(service.isVideoOnly("宫保鸡丁要哪些食材，想看视频", Map.of())).isFalse();
        assertThat(service.isVideoOnly("只看视频，不用文字菜谱，宫保鸡丁需要什么食材", Map.of())).isTrue();
        assertThat(service.isVideoOnly("想学这个菜，有视频吗",
                Map.of("recipeQuery", Map.of("query", "宫保鸡丁怎么做")))).isFalse();
        assertThat(service.isVideoOnly("宫保鸡丁需要什么食材",
                Map.of("videoSearch", Map.of("query", "宫保鸡丁")))).isFalse();
        assertThat(service.isVideoOnly("ignored", Map.of("videoSearch", Map.of("query", "宫保鸡丁"))))
                .isTrue();
    }

    @Test
    void buildsCompactVideoRequestForOrdinaryRecipeQuestion() {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        properties.setDefaultLimit(3);
        var service = new CookingVideoSearchService(new FakeCookingVideoSearchProvider(), properties);

        var request = service.forRecipe("宫保鸡丁咋做啊");

        assertThat(request.query()).isEqualTo("宫保鸡丁");
        assertThat(request.platform()).isEqualTo(VideoPlatform.ANY);
        assertThat(request.limit()).isEqualTo(3);
        assertThat(service.forRecipe("宫保鸡丁视频教程").query()).isEqualTo("宫保鸡丁");
        assertThat(service.forRecipe("宫保鸡丁需要什么食材，有视频吗").query()).isEqualTo("宫保鸡丁");
    }
}
