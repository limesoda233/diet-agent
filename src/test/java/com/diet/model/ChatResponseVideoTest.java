package com.diet.model;

import com.diet.service.video.CookingVideo;
import com.diet.service.video.VideoPlatform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatResponseVideoTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void recipeResponseCanContainTextAndVideoCards() throws Exception {
        var video = new CookingVideo("宫保鸡丁", "教学视频", "https://www.bilibili.com/video/BV1abc",
                VideoPlatform.BILIBILI, "厨房", 120, "mcp:bilibili");
        Method factory = ChatResponse.class.getMethod(
                "recipe", String.class, String.class, String.class, List.class);
        ChatResponse response = (ChatResponse) factory.invoke(null, "s", "t", "宫保鸡丁做法 [S1]", List.of(video));
        JsonNode serialized = json.valueToTree(response);
        assertThat(serialized.path("responseType").asText()).isEqualTo("ANSWER");
        assertThat(serialized.path("speechText").asText()).contains("[S1]");
        assertThat(serialized.path("videoBlocks")).hasSize(1);
        assertThat(serialized.path("displayBlocks")).isEmpty();
        assertThat(serialized.path("planBlocks")).isEmpty();
        assertThat(serialized.path("nextAction").asText()).isEqualTo("WAIT_USER");
        assertThat(serialized.path("sessionId").asText()).isEqualTo("s");
        assertThat(serialized.path("traceId").asText()).isEqualTo("t");
        ChatResponse withoutVideos = (ChatResponse) factory.invoke(null, "s", "t", "文字 [S1]", null);
        assertThat(withoutVideos.videoBlocks()).isEmpty();
    }

    @Test
    void ordinaryAnswersExposeAnEmptyVideoBlockList() {
        JsonNode response = json.valueToTree(ChatResponse.answer("session", "trace", "text", List.of(), "WAIT_USER"));

        assertThat(response.path("videoBlocks").isArray()).isTrue();
        assertThat(response.path("videoBlocks")).isEmpty();
    }

    @Test
    void videoFactoryReturnsStructuredCards() throws Exception {
        Method factory = ChatResponse.class.getMethod(
                "videos", String.class, String.class, String.class, List.class);
        var video = new CookingVideo("番茄炒蛋", "番茄炒蛋教程",
                "https://www.bilibili.com/video/BV1abc", VideoPlatform.BILIBILI,
                "厨房阿姨", 332, "mcp:bilibili");

        ChatResponse response = (ChatResponse) factory.invoke(null,
                "session", "trace", "找到 1 个教学视频。", List.of(video));
        JsonNode serialized = json.valueToTree(response);

        assertThat(serialized.path("responseType").asText()).isEqualTo("VIDEOS");
        assertThat(serialized.path("displayBlocks")).isEmpty();
        assertThat(serialized.path("videoBlocks")).singleElement().satisfies(card -> {
            assertThat(card.path("title").asText()).isEqualTo("番茄炒蛋教程");
            assertThat(card.path("url").asText()).isEqualTo("https://www.bilibili.com/video/BV1abc");
            assertThat(card.path("platform").asText()).isEqualTo("BILIBILI");
            assertThat(card.path("creator").asText()).isEqualTo("厨房阿姨");
            assertThat(card.path("durationSeconds").asInt()).isEqualTo(332);
            assertThat(card.has("source")).isFalse();
            assertThat(card.has("query")).isFalse();
        });
    }
}
