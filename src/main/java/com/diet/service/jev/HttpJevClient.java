package com.diet.service.jev;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class HttpJevClient implements JevClient {
    private static final int MAX_RESPONSE_LENGTH = 4_000_000;

    private final JevProperties properties;
    private final HttpClient httpClient;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public HttpJevClient(JevProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public JevResponse systemOne(Object state, Map<String, Object> questions) {
        if (!properties.configured()) {
            throw new JevClientException("jev_not_configured");
        }
        if (state == null || questions == null || questions.isEmpty()) {
            throw new JevClientException("jev_invalid_request");
        }
        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new JevClientException("jev_invalid_timeout");
        }

        try {
            String payload = json.writeValueAsString(Map.of(
                    "state", state,
                    "model", properties.getModel(),
                    "questions", questions
            ));
            HttpRequest request = HttpRequest.newBuilder(safeEndpoint(properties.getEndpoint()))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new JevClientException("jev_http_" + response.statusCode());
            }
            return parse(response.body());
        } catch (JevClientException error) {
            throw error;
        } catch (HttpTimeoutException error) {
            throw new JevClientException("jev_timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new JevClientException("jev_interrupted");
        } catch (Exception error) {
            throw new JevClientException("jev_invalid_response_or_connection");
        }
    }

    private JevResponse parse(String body) throws Exception {
        if (body == null || body.length() > MAX_RESPONSE_LENGTH) {
            throw new JevClientException("jev_response_too_large");
        }
        JsonNode root = json.readTree(body);
        JsonNode answersNode = root.path("answers");
        if (!answersNode.isObject() || answersNode.isEmpty()) {
            throw new JevClientException("jev_answers_missing");
        }
        Map<String, JevAnswer> answers = new LinkedHashMap<>();
        answersNode.fields().forEachRemaining(entry -> answers.put(entry.getKey(), parseAnswer(entry.getValue())));
        JsonNode usage = root.path("usage");
        return new JevResponse(
                root.path("model").asText(properties.getModel()),
                Map.copyOf(answers),
                completeUsage(usage)
        );
    }

    /** Missing or malformed usage is unknown, not free; it must not invalidate an otherwise valid decision. */
    private JevUsage completeUsage(JsonNode usage) {
        JsonNode input = usage.path("input_tokens"), output = usage.path("output_tokens");
        if (!input.isIntegralNumber() || !output.isIntegralNumber()
                || !input.canConvertToLong() || !output.canConvertToLong()
                || input.longValue() < 0 || output.longValue() < 0) return null;
        return new JevUsage(input.longValue(), output.longValue());
    }

    private JevAnswer parseAnswer(JsonNode node) {
        String type = node.path("type").asText("");
        String choice = node.hasNonNull("choice") ? node.path("choice").asText() : null;
        Double score = finiteNumber(node, "score");
        Double noul = probability(node, "noul");
        Double confidence = probability(node, "confidence");
        Map<String, Double> probabilities = new LinkedHashMap<>();
        JsonNode probabilitiesNode = node.path("probabilities");
        if (probabilitiesNode.isObject()) {
            probabilitiesNode.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isNumber() || !Double.isFinite(entry.getValue().doubleValue())) {
                    throw new JevClientException("jev_probability_invalid");
                }
                probabilities.put(entry.getKey(), entry.getValue().doubleValue());
            });
        }
        return new JevAnswer(type, choice, score, noul, confidence, Map.copyOf(probabilities));
    }

    private Double finiteNumber(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        if (!node.path(field).isNumber() || !Double.isFinite(node.path(field).doubleValue())) {
            throw new JevClientException("jev_" + field + "_invalid");
        }
        return node.path(field).doubleValue();
    }

    private Double probability(JsonNode node, String field) {
        Double value = finiteNumber(node, field);
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new JevClientException("jev_" + field + "_invalid");
        }
        return value;
    }

    static URI safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            boolean localHttp = "http".equals(uri.getScheme())
                    && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || localHttp)) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception error) {
            throw new JevClientException("jev_invalid_endpoint");
        }
    }
}
