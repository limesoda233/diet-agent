package com.diet.service.video;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.StaticApplicationContext;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BilibiliMcpWarmupTest {
    @Test
    void enabledRealProviderStartsWarmupWithoutBlockingApplicationReady() {
        var started = new AtomicInteger();
        var ready = Sinks.<Void>empty();
        var client = mock(AgentScopeBilibiliMcpClient.class);
        when(client.warmUp()).thenReturn(Mono.defer(() -> {
            started.incrementAndGet();
            return ready.asMono();
        }));
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        properties.setProvider("bilibili-mcp");
        var listener = new VideoMcpConfiguration().bilibiliMcpWarmup(properties, client);

        listener.onApplicationEvent(readyEvent());

        assertThat(started.get()).isEqualTo(1);
        ready.tryEmitEmpty();
    }

    @Test
    void disabledOrFakeProviderDoesNotConnectAtStartup() {
        for (String provider : new String[]{"disabled", "fake", "bilibili-mcp"}) {
            var client = mock(AgentScopeBilibiliMcpClient.class);
            var started = new AtomicInteger();
            when(client.warmUp()).thenReturn(Mono.fromRunnable(started::incrementAndGet));
            var properties = new VideoMcpProperties();
            properties.setEnabled(!"bilibili-mcp".equals(provider));
            properties.setProvider(provider);
            new VideoMcpConfiguration().bilibiliMcpWarmup(properties, client).onApplicationEvent(readyEvent());
            assertThat(started.get()).isZero();
        }
    }

    @Test
    void startupFailureIsHandledWithoutFailingTheApplication() {
        var client = mock(AgentScopeBilibiliMcpClient.class);
        when(client.warmUp()).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        properties.setProvider("bilibili-mcp");
        var listener = new VideoMcpConfiguration().bilibiliMcpWarmup(properties, client);

        assertThatCode(() -> listener.onApplicationEvent(readyEvent())).doesNotThrowAnyException();
    }

    private ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0],
                new StaticApplicationContext(), Duration.ZERO);
    }
}
