package com.diet.service.video;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMcpProfileConfigurationTest {

    @Test
    void bindsTheAcceptedVideoMcpProfileDefaults() throws Exception {
        var sources = new YamlPropertySourceLoader().load(
                "video-mcp", new ClassPathResource("application-video-mcp.yml"));
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(sources.get(0));
        VideoMcpProperties properties = Binder.get(environment)
                .bind("diet.video-mcp", Bindable.of(VideoMcpProperties.class))
                .get();

        assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(properties.getDefaultLimit()).isEqualTo(3);
        assertThat(properties.getQuerySuffix()).isEqualTo(" 做法 教程");
    }

    @Test
    void leavesVideoSearchDisabledByDefault() {
        assertThat(new VideoMcpProperties().isEnabled()).isFalse();
    }
}
