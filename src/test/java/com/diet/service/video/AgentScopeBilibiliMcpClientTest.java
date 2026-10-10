package com.diet.service.video;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentScopeBilibiliMcpClientTest {

    @Test
    void initializesDiscoversAndCallsPinnedSearchTool() {
        var fake = new FakeMcpClient(true, "server text");
        var properties = properties();
        var client = new AgentScopeBilibiliMcpClient(properties, () -> fake);

        String result = client.searchVideos("宫保鸡丁 做法 教程", 3).block(Duration.ofSeconds(1));

        assertThat(result).isEqualTo("server text");
        assertThat(fake.initializeCalls).isEqualTo(1);
        assertThat(fake.calledTool).isEqualTo("search_videos");
        assertThat(fake.arguments).containsEntry("keyword", "宫保鸡丁 做法 教程")
                .containsEntry("page", 1).containsEntry("count", 3);
        client.close();
        assertThat(fake.closed).isTrue();
    }

    @Test
    void failsWhenPinnedServerDoesNotExposeExpectedTool() {
        var client = new AgentScopeBilibiliMcpClient(properties(), () -> new FakeMcpClient(false, "ignored"));

        assertThatThrownBy(() -> client.searchVideos("宫保鸡丁", 3).block(Duration.ofSeconds(1)))
                .hasMessageContaining("bilibili_mcp_tool_missing");
    }

    @Test
    void timedOutSearchDoesNotCancelSharedInitializationAndNextSearchUsesReadyClient() throws Exception {
        var fake = new DelayedMcpClient();
        var creations = new AtomicInteger();
        var properties = properties();
        properties.setEnabled(true);
        properties.setTimeout(Duration.ofMillis(100));
        properties.setInitializationTimeout(Duration.ofSeconds(5));
        var client = new AgentScopeBilibiliMcpClient(properties, () -> {
            creations.incrementAndGet();
            return fake;
        });
        var service = new CookingVideoSearchService(
                new BilibiliMcpCookingVideoSearchProvider(client, ""), properties);
        try {
            assertThatThrownBy(() -> service.search("番茄炒蛋", VideoPlatform.BILIBILI, 1)
                    .block(Duration.ofSeconds(2))).hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(fake.started.await(1, TimeUnit.SECONDS)).isTrue();
            // Give the old blocking worker time to observe interruption and close the client.
            assertThat(fake.cancelled.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(fake.closed).isFalse();

            fake.ready.tryEmitEmpty();
            properties.setTimeout(Duration.ofSeconds(2));
            var videos = service.search("番茄炒蛋", VideoPlatform.BILIBILI, 1)
                    .block(Duration.ofSeconds(3));
            assertThat(videos).hasSize(1);
            assertThat(videos.get(0).url()).isEqualTo("https://www.bilibili.com/video/BV1234567890");
            assertThat(creations.get()).isEqualTo(1);
            assertThat(fake.initializeCalls.get()).isEqualTo(1);
            assertThat(fake.toolCalls.get()).isEqualTo(1);
        } finally {
            client.close();
        }
    }

    @Test
    void concurrentSearchesShareOneInitializationAndReceiveTheirOwnToolResults() throws Exception {
        var fake = new DelayedMcpClient();
        var client = new AgentScopeBilibiliMcpClient(properties(), () -> fake);
        var first = new CompletableFuture<String>();
        var second = new CompletableFuture<String>();
        var secondError = new AtomicReference<Throwable>();
        var request1 = client.searchVideos("first", 1).subscribe(first::complete, first::completeExceptionally);
        try {
            assertThat(fake.started.await(1, TimeUnit.SECONDS)).isTrue();
            // Subscribing while initialization is pending must not wait on a monitor.
            var request2 = client.searchVideos("second", 1).subscribe(second::complete, error -> {
                secondError.set(error);
                second.completeExceptionally(error);
            });
            try {
                fake.ready.tryEmitEmpty();
                assertThat(first.get(2, TimeUnit.SECONDS)).contains("BV1234567890");
                assertThat(second.get(2, TimeUnit.SECONDS)).contains("BV1234567890");
                assertThat(secondError.get()).isNull();
                assertThat(fake.initializeCalls.get()).isEqualTo(1);
                assertThat(fake.toolCalls.get()).isEqualTo(2);
            } finally {
                request2.dispose();
            }
        } finally {
            request1.dispose();
            client.close();
        }
    }

    @Test
    void failedInitializationClosesCandidateAndLaterRequestCanRetry() {
        var failed = new FakeMcpClient(false, "ignored");
        var ready = new FakeMcpClient(true, "retried result");
        var creations = new AtomicInteger();
        var client = new AgentScopeBilibiliMcpClient(properties(),
                () -> creations.getAndIncrement() == 0 ? failed : ready);
        try {
            assertThatThrownBy(() -> client.searchVideos("菜谱", 1).block(Duration.ofSeconds(2)))
                    .hasMessageContaining("bilibili_mcp_tool_missing");
            assertThat(failed.closed).isTrue();
            assertThat(client.searchVideos("菜谱", 1).block(Duration.ofSeconds(2)))
                    .isEqualTo("retried result");
            assertThat(creations.get()).isEqualTo(2);
        } finally {
            client.close();
        }
    }

    @Test
    void initializationHasItsOwnDeadlineAndClosesTimedOutCandidate() {
        var fake = new DelayedMcpClient();
        var properties = properties();
        properties.setInitializationTimeout(Duration.ofMillis(100));
        var client = new AgentScopeBilibiliMcpClient(properties, () -> fake);
        try {
            assertThatThrownBy(() -> client.searchVideos("菜谱", 1).block(Duration.ofSeconds(2)))
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(fake.closed).isTrue();
            assertThat(fake.toolCalls.get()).isZero();
        } finally {
            client.close();
        }
    }

    @Test
    void initializedToolCallStillHonorsSearchDeadline() {
        var fake = new FakeMcpClient(true, "late result");
        fake.toolDelay = Duration.ofSeconds(1);
        var properties = properties();
        properties.setTimeout(Duration.ofMillis(100));
        var client = new AgentScopeBilibiliMcpClient(properties, () -> fake);
        try {
            client.warmUp().block(Duration.ofSeconds(2));
            assertThatThrownBy(() -> client.searchVideos("菜谱", 1).block(Duration.ofSeconds(2)))
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(fake.closed).isFalse();
        } finally {
            client.close();
        }
    }

    @Test
    void closeDuringInitializationClosesCandidateAndPreventsLaterToolCalls() throws Exception {
        var fake = new DelayedMcpClient();
        var client = new AgentScopeBilibiliMcpClient(properties(), () -> fake);
        var result = new CompletableFuture<String>();
        var request = client.searchVideos("菜谱", 1).subscribe(result::complete, result::completeExceptionally);
        try {
            assertThat(fake.started.await(1, TimeUnit.SECONDS)).isTrue();
            client.close();
            fake.ready.tryEmitEmpty();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS)).hasMessageContaining("bilibili_mcp_closed");
            assertThatThrownBy(() -> client.searchVideos("菜谱", 1).block(Duration.ofSeconds(1)))
                    .hasMessageContaining("bilibili_mcp_closed");
            assertThat(fake.closed).isTrue();
            assertThat(fake.toolCalls.get()).isZero();
        } finally {
            request.dispose();
            client.close();
        }
    }

    @Test
    void closeDuringClientCreationReleasesTheLateReturnedCandidate() throws Exception {
        var fake = new DelayedMcpClient();
        var creating = new CountDownLatch(1);
        var allowReturn = new CountDownLatch(1);
        var client = new AgentScopeBilibiliMcpClient(properties(), () -> {
            creating.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    allowReturn.await();
                    waiting = false;
                } catch (InterruptedException ignored) {
                    // Model a resource factory that finishes allocating after cancellation.
                }
            }
            return fake;
        });
        var failure = new CompletableFuture<Throwable>();
        var request = client.searchVideos("菜谱", 1).subscribe(ignored -> { }, failure::complete);
        try {
            assertThat(creating.await(1, TimeUnit.SECONDS)).isTrue();
            client.close();
            allowReturn.countDown();
            assertThat(failure.get(1, TimeUnit.SECONDS)).hasMessage("bilibili_mcp_closed");
            assertThat(fake.released.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(fake.closeCalls.get()).isEqualTo(1);
            assertThat(fake.initializeCalls.get()).isZero();
            assertThat(fake.toolCalls.get()).isZero();
        } finally {
            allowReturn.countDown();
            request.dispose();
            client.close();
        }
    }

    private VideoMcpProperties properties() {
        var properties = new VideoMcpProperties();
        properties.setInitializationTimeout(Duration.ofSeconds(1));
        properties.setTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static final class FakeMcpClient extends McpClientWrapper {
        private final boolean exposeSearch;
        private final String resultText;
        private int initializeCalls;
        private String calledTool;
        private Map<String, Object> arguments;
        private boolean closed;
        private Duration toolDelay = Duration.ZERO;

        private FakeMcpClient(boolean exposeSearch, String resultText) {
            super("fake-bilibili");
            this.exposeSearch = exposeSearch;
            this.resultText = resultText;
        }

        @Override
        public Mono<Void> initialize() {
            initializeCalls++;
            initialized = true;
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            if (!exposeSearch) return Mono.just(List.of());
            return Mono.just(List.of(McpSchema.Tool.builder()
                    .name("search_videos")
                    .description("search")
                    .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of()))
                    .build()));
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            this.calledTool = toolName;
            this.arguments = arguments;
            return Mono.just(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(resultText)), false))
                    .delayElement(toolDelay);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class DelayedMcpClient extends McpClientWrapper {
        final Sinks.Empty<Void> ready = Sinks.empty();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicInteger initializeCalls = new AtomicInteger();
        final AtomicInteger toolCalls = new AtomicInteger();
        final AtomicInteger closeCalls = new AtomicInteger();
        volatile boolean closed;

        DelayedMcpClient() { super("delayed-bilibili"); }

        @Override
        public Mono<Void> initialize() {
            return Mono.defer(() -> {
                initializeCalls.incrementAndGet();
                started.countDown();
                return ready.asMono().doOnCancel(cancelled::countDown)
                        .doOnSuccess(ignored -> initialized = true);
            });
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(List.of(McpSchema.Tool.builder().name("search_videos")
                    .description("search")
                    .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, Map.of(), Map.of()))
                    .build()));
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(String toolName, Map<String, Object> arguments) {
            return Mono.fromSupplier(() -> {
                toolCalls.incrementAndGet();
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(
                        "1. \"番茄炒蛋\" - 厨师\nBV ID: BV1234567890\nDuration: 03:20")), false);
            });
        }

        @Override
        public void close() { closeCalls.incrementAndGet(); closed = true; initialized = false; released.countDown(); }
    }
}
