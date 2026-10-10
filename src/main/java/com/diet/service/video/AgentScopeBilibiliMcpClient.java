package com.diet.service.video;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * AgentScope 的 stdio MCP 网关：启动固定第三方进程、握手、发现 search_videos、复用客户端。
 * <p>初始化属于应用生命周期，而不是某次聊天请求。共享 cache 让多个请求等待同一次握手，
 * 一个请求达到业务超时时只取消自己的等待；初始化仍由独立预算和应用关闭负责结束。
 * 失败候选释放后允许下一次重建，不自动重试已经失败的工具搜索。</p>
 * SDK 的初始化 RPC 也使用 requestTimeout，因此 SDK 预算必须覆盖握手；
 * 实际工具调用另外受业务 searchTimeout 限制，不能因冷启动把请求无限延长。
 */
public final class AgentScopeBilibiliMcpClient implements BilibiliVideoMcpGateway, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeBilibiliMcpClient.class);
    private final VideoMcpProperties properties;
    private final Mono<McpClientWrapper> clientCreation;
    private final Object lifecycleMonitor = new Object();
    private final Sinks.Empty<Void> shutdown = Sinks.empty();
    private InitializationAttempt initialization;
    private boolean closed;

    public AgentScopeBilibiliMcpClient(VideoMcpProperties properties) {
        this(properties, Mono.defer(() -> McpClientBuilder.create(properties.getServerName())
                .stdioTransport(properties.getCommand(), properties.getArguments(), Map.of())
                // MCP's initialize RPC also uses requestTimeout internally.
                // Give the handshake its full budget; tool calls have their own deadline below.
                .timeout(sdkRequestTimeout(properties))
                .initializationTimeout(properties.getInitializationTimeout())
                .buildAsync()));
    }

    AgentScopeBilibiliMcpClient(VideoMcpProperties properties, Supplier<McpClientWrapper> clientFactory) {
        this(properties, Mono.fromSupplier(clientFactory));
    }

    private AgentScopeBilibiliMcpClient(VideoMcpProperties properties, Mono<McpClientWrapper> clientCreation) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clientCreation = Objects.requireNonNull(clientCreation, "clientCreation");
    }

    @Override
    public Mono<String> searchVideos(String keyword, int count) {
        // 业务层另包总预算；这里限制工具调用，初始化超时由共享初始化流程管理。
        return Mono.defer(this::initializedClient)
                .flatMap(active -> Mono.defer(() -> active.callTool(properties.getSearchTool(), Map.of(
                        "keyword", keyword,
                        "page", 1,
                        "count", count)))
                        .timeout(searchTimeout(properties)))
                .map(this::textContent);
    }

    public Mono<Void> warmUp() {
        // 启用真实 provider 后由应用就绪事件订阅；构造 Bean 时不阻塞启动线程。
        return Mono.defer(this::initializedClient).then();
    }

    private Mono<McpClientWrapper> initializedClient() {
        synchronized (lifecycleMonitor) {
            if (closed) return Mono.error(new IllegalStateException("bilibili_mcp_closed"));
            if (initialization == null) {
                InitializationAttempt attempt = new InitializationAttempt();
                // Request cancellation must not interrupt the shared process handshake.
                // Its own deadline and application shutdown still release the candidate.
                attempt.result = createInitialization(attempt).cache();
                initialization = attempt;
            }
            return initialization.result;
        }
    }

    private Mono<McpClientWrapper> createInitialization(InitializationAttempt attempt) {
        Duration timeout = properties.getInitializationTimeout() == null
                ? Duration.ofSeconds(30) : properties.getInitializationTimeout();
        // Acquire under lifecycle ownership before usingWhen can cancel/discard a
        // factory's late return. Once acquisition starts it registers or releases
        // the candidate even if application shutdown has removed its subscriber.
        Mono<McpClientWrapper> acquisition = clientCreation
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(candidate -> registerCandidate(attempt, candidate))
                .cache();
        return Mono.<McpClientWrapper, McpClientWrapper>usingWhen(
                        acquisition,
                        candidate -> initializeAndDiscover(attempt, candidate).timeout(timeout),
                        candidate -> Mono.empty(),
                        (candidate, error) -> release(attempt),
                        candidate -> release(attempt))
                .takeUntilOther(shutdown.asMono())
                .switchIfEmpty(Mono.error(new IllegalStateException("bilibili_mcp_closed")))
                .doOnNext(ignored -> log.info("Bilibili MCP ready; search tool verified"))
                .doOnError(error -> {
                    synchronized (lifecycleMonitor) {
                        if (initialization == attempt) initialization = null;
                    }
                    log.warn("Bilibili MCP initialization unavailable: {}", error.getClass().getSimpleName());
                });
    }

    private static Duration searchTimeout(VideoMcpProperties properties) {
        return properties.getTimeout() == null ? Duration.ofSeconds(3) : properties.getTimeout();
    }

    private static Duration sdkRequestTimeout(VideoMcpProperties properties) {
        Duration initialization = properties.getInitializationTimeout() == null
                ? Duration.ofSeconds(30) : properties.getInitializationTimeout();
        Duration search = searchTimeout(properties);
        return initialization.compareTo(search) > 0 ? initialization : search;
    }

    private Mono<McpClientWrapper> registerCandidate(InitializationAttempt attempt, McpClientWrapper candidate) {
        boolean alreadyClosed;
        synchronized (lifecycleMonitor) {
            attempt.candidate = candidate;
            alreadyClosed = closed;
        }
        return alreadyClosed ? release(attempt).then(Mono.error(new IllegalStateException("bilibili_mcp_closed")))
                : Mono.just(candidate);
    }

    private Mono<McpClientWrapper> initializeAndDiscover(InitializationAttempt attempt, McpClientWrapper candidate) {
        synchronized (lifecycleMonitor) {
            if (closed) return Mono.error(new IllegalStateException("bilibili_mcp_closed"));
        }
        log.info("Initializing Bilibili MCP asynchronously");
        return Mono.defer(candidate::initialize)
                .then(Mono.defer(candidate::listTools))
                .switchIfEmpty(Mono.error(new IllegalStateException("bilibili_mcp_tool_missing")))
                .flatMap(tools -> tools.stream().anyMatch(tool -> properties.getSearchTool().equals(tool.name()))
                        ? Mono.just(candidate)
                        : Mono.error(new IllegalStateException("bilibili_mcp_tool_missing")));
    }

    private Mono<Void> release(InitializationAttempt attempt) {
        // SDK close can block; never run it on Reactor's timeout/event-loop thread.
        return Mono.<Void>fromRunnable(() -> closeCandidate(attempt)).subscribeOn(Schedulers.boundedElastic());
    }

    private void closeCandidate(InitializationAttempt attempt) {
        // 超时清理、应用关闭和迟到的 factory 结果可能竞争，CAS 保证同一候选只关闭一次。
        if (attempt == null || attempt.candidate == null || !attempt.released.compareAndSet(false, true)) return;
        try {
            attempt.candidate.close();
        } catch (RuntimeException error) {
            log.warn("Bilibili MCP close failed: {}", error.getClass().getSimpleName());
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
        InitializationAttempt attempt;
        synchronized (lifecycleMonitor) {
            if (closed) return;
            closed = true;
            attempt = initialization;
        }
        shutdown.tryEmitEmpty();
        closeCandidate(attempt);
    }

    private static final class InitializationAttempt {
        final AtomicBoolean released = new AtomicBoolean();
        volatile McpClientWrapper candidate;
        Mono<McpClientWrapper> result;
    }
}
