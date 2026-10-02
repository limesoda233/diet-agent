package com.diet.service.video;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class AgentScopeBilibiliMcpClient implements BilibiliVideoMcpGateway, AutoCloseable {
    private final VideoMcpProperties properties;
    private final Supplier<McpClientWrapper> clientFactory;
    private final Object lifecycleMonitor = new Object();
    private volatile McpClientWrapper client;

    public AgentScopeBilibiliMcpClient(VideoMcpProperties properties) {
        this(properties, () -> McpClientBuilder.create(properties.getServerName())
                .stdioTransport(properties.getCommand(), properties.getArguments(), Map.of())
                .timeout(properties.getTimeout())
                .initializationTimeout(properties.getInitializationTimeout())
                .buildSync());
    }

    AgentScopeBilibiliMcpClient(VideoMcpProperties properties, Supplier<McpClientWrapper> clientFactory) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public Mono<String> searchVideos(String keyword, int count) {
        return Mono.fromCallable(this::initializedClient)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(active -> active.callTool(properties.getSearchTool(), Map.of(
                        "keyword", keyword,
                        "page", 1,
                        "count", count)))
                .map(this::textContent);
    }

    private McpClientWrapper initializedClient() {
        McpClientWrapper current = client;
        if (current != null && current.isInitialized()) return current;
        synchronized (lifecycleMonitor) {
            current = client;
            if (current != null && current.isInitialized()) return current;
            McpClientWrapper candidate = clientFactory.get();
            Duration timeout = properties.getInitializationTimeout() == null
                    ? Duration.ofSeconds(30) : properties.getInitializationTimeout();
            try {
                candidate.initialize().block(timeout);
                List<McpSchema.Tool> tools = candidate.listTools().block(timeout);
                boolean available = tools != null && tools.stream()
                        .anyMatch(tool -> properties.getSearchTool().equals(tool.name()));
                if (!available) throw new IllegalStateException("bilibili_mcp_tool_missing:" + properties.getSearchTool());
                client = candidate;
                return candidate;
            } catch (RuntimeException error) {
                candidate.close();
                throw error;
            }
        }
    }

    private String textContent(McpSchema.CallToolResult result) {
        if (result == null) throw new IllegalStateException("bilibili_mcp_empty_result");
        String text = result.content() == null ? "" : result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("bilibili_mcp_tool_error:" + text);
        }
        if (text.isBlank()) throw new IllegalStateException("bilibili_mcp_empty_result");
        if (text.startsWith("Failed to search videos:")) {
            throw new IllegalStateException("bilibili_mcp_search_failed:" + text);
        }
        return text;
    }

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (client != null) {
                client.close();
                client = null;
            }
        }
    }
}
