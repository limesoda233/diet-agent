package com.diet.service.video;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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
            return Mono.just(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(resultText)), false));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
