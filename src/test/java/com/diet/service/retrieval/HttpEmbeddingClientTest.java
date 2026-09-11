package com.diet.service.retrieval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HttpEmbeddingClientTest {
    @Test void timesOutWithoutExposingRequestCredentials() throws Exception {
        try(var fixture=new Fixture()) {
            fixture.delayMs=300;
            var config=fixture.configuration(); config.setTimeout(java.time.Duration.ofMillis(40));
            assertThatThrownBy(()->new HttpEmbeddingClient(config,HttpClient.newHttpClient()).embedQuery("查询"))
                    .hasMessage("embedding_timeout").hasMessageNotContaining("test-key");
        }
    }
    @Test void restoresBatchOrderAndRejectsIncompleteOrInvalidVectors() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.body = "{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}";
            var result = fixture.client().embedDocuments(List.of("甲", "乙"));
            assertThat(result.get(0)).containsExactly(1f, 0f);
            assertThat(result.get(1)).containsExactly(0f, 1f);
            for (String body : List.of("{\"data\":[]}", "{\"data\":[{\"index\":0,\"embedding\":[0,0]}]}",
                    "{\"data\":[{\"index\":0,\"embedding\":[1]}]}", "{\"data\":[{\"index\":0.5,\"embedding\":[1,0]}]}",
                    "{\"data\":[{\"index\":0,\"embedding\":[\"1\",0]}]}")) {
                fixture.body = body;
                assertThatThrownBy(() -> fixture.client().embedQuery("查询")).isInstanceOf(RetrievalDependencyException.class);
            }
        }
    }
    @Test void retriesRateLimitOnceButDoesNotRetryAuthenticationFailure() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.status = 429;
            var client=fixture.client();
            assertThatThrownBy(() -> client.embedQuery("查询")).hasMessageContaining("429");
            assertThat(client.lastRetryCount()).isEqualTo(1);
            assertThat(fixture.calls.get()).isEqualTo(2);
            fixture.calls.set(0); fixture.status = 401;
            assertThatThrownBy(() -> client.embedQuery("查询")).hasMessageContaining("401").hasMessageNotContaining("test-key");
            assertThat(client.lastRetryCount()).isZero();
            assertThat(fixture.calls.get()).isEqualTo(1);
        }
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger calls = new AtomicInteger();
        volatile String body = "{}";
        volatile int status = 200;
        volatile long delayMs;
        Fixture() throws Exception {
            server.createContext("/embeddings", exchange -> {
                calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                if(delayMs>0) try { Thread.sleep(delayMs); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                byte[] data = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, data.length); exchange.getResponseBody().write(data); exchange.close();
            });
            server.start();
        }
        HttpEmbeddingClient client() {
            return new HttpEmbeddingClient(configuration(), HttpClient.newHttpClient());
        }
        RetrievalProperties.Embedding configuration() {
            var config = new RetrievalProperties.Embedding();
            config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
            config.setApiKey("test-key"); config.setModel("fixture"); config.setDimensions(2);
            return config;
        }
        public void close() { server.stop(0); }
    }
}
