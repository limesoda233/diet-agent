package com.diet.service.jev;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpJevClientTest {

    @Test
    void sendsSystemOneRequestAndParsesTypedAnswers() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.responseBody = """
                    {
                      "model": "jev-1.13.0",
                      "answers": {
                        "intent": {
                          "type": "choice",
                          "choice": "MEAL_PLAN",
                          "confidence": 0.92,
                          "probabilities": {"MEAL_PLAN": 0.96, "OTHER": 0.04}
                        },
                        "quality": {
                          "type": "score",
                          "score": 3.25,
                          "confidence": 0.75,
                          "probabilities": {"3": 0.75, "4": 0.25}
                        },
                        "slot_mealTime_0": {"type": "noul", "noul": 0.88}
                      },
                      "usage": {"input_tokens": 100, "output_tokens": 12}
                    }
                    """;

            JevResponse response = fixture.client().systemOne(
                    Map.of("currentMessage", "早晚餐一起推荐"),
                    Map.of("intent", Map.of("type", "choice", "instructions", "识别意图", "criteria", Map.of("MEAL_PLAN", "多餐规划")))
            );

            assertThat(fixture.authorization.get()).isEqualTo("Bearer test-jev-key");
            assertThat(fixture.requestBody.get()).contains("\"model\":\"jev-latest\"")
                    .contains("\"currentMessage\":\"早晚餐一起推荐\"")
                    .contains("\"questions\"");
            assertThat(response.model()).isEqualTo("jev-1.13.0");
            assertThat(response.answers().get("intent").choice()).isEqualTo("MEAL_PLAN");
            assertThat(response.answers().get("quality").score()).isEqualTo(3.25);
            assertThat(response.answers().get("slot_mealTime_0").noul()).isEqualTo(0.88);
            assertThat(response.usage().inputTokens()).isEqualTo(100L);
        }
    }

    @Test
    void rejectsAuthenticationFailureWithoutLeakingApiKey() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.status = 401;

            assertThatThrownBy(() -> fixture.client().systemOne(
                    "hello",
                    Map.of("check", Map.of("type", "noul", "instructions", "是否是问候"))
            ))
                    .isInstanceOf(JevClientException.class)
                    .hasMessage("jev_http_401")
                    .hasMessageNotContaining("test-jev-key");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> requestBody = new AtomicReference<>();
        private volatile int status = 200;
        private volatile String responseBody = "{}";

        private Fixture() throws Exception {
            server.createContext("/v1/systemone", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] data = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, data.length);
                exchange.getResponseBody().write(data);
                exchange.close();
            });
            server.start();
        }

        private HttpJevClient client() {
            JevProperties properties = new JevProperties();
            properties.setApiKey("test-jev-key");
            properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone");
            return new HttpJevClient(properties, HttpClient.newHttpClient());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
