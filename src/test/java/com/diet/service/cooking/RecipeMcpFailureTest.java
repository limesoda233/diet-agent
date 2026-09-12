package com.diet.service.cooking;

import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipeMcpFailureTest {
    public static class SleepingProcess {
        public static void main(String[] args) throws Exception { new CountDownLatch(1).await(); }
    }
    @Test void boundsBlockedSdkCloseAndTerminatesOnlyItsOwnChild() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),SleepingProcess.class.getName()).start();
        var release=new CountDownLatch(1);
        var wrapper=mock(McpClientWrapper.class);
        doAnswer(invocation->{ release.await(); return null; }).when(wrapper).close();
        try {
            var client=new RecipeMcpClient(new CookingProperties(),wrapper,child.toHandle());
            long started=System.nanoTime();
            client.close();
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(4));
            assertThat(child.isAlive()).isFalse();
        } finally { release.countDown(); child.destroyForcibly(); }
    }
    @Test void timesOutAndClosesTheFailedConnection() {
        var wrapper=mock(McpClientWrapper.class);
        when(wrapper.callTool(anyString(),anyMap())).thenReturn(Mono.never());
        var properties=new CookingProperties(); properties.setTimeout(Duration.ofMillis(50));
        try(var client=new RecipeMcpClient(properties,wrapper)) {
            long started=System.nanoTime();
            assertThatThrownBy(()->client.call("search_recipes",Map.of())).isInstanceOf(IllegalStateException.class).hasMessage("recipe_mcp_unavailable");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));
            verify(wrapper).close();
        }
    }
    @Test void doesNotReconnectWhenCloseTimesOutBeforeProcessIdentityIsKnown() {
        var wrapper=mock(McpClientWrapper.class);
        when(wrapper.callTool(anyString(),anyMap())).thenReturn(Mono.never());
        doAnswer(invocation->{ new CountDownLatch(1).await(); return null; }).when(wrapper).close();
        var properties=new CookingProperties(); properties.setTimeout(Duration.ofMillis(50));
        try(var client=new RecipeMcpClient(properties,wrapper)) {
            assertThatThrownBy(()->client.call("search_recipes",Map.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->client.call("search_recipes",Map.of())).hasRootCauseMessage("recipe_mcp_closed");
        }
    }
}
