package com.diet.service.retrieval;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

public class HttpEmbeddingClient implements EmbeddingClient {
    private final RetrievalProperties.Embedding config;
    private final HttpClient http;
    private final ThreadLocal<Integer> retries=ThreadLocal.withInitial(()->0);
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public HttpEmbeddingClient(RetrievalProperties.Embedding config, HttpClient http) { this.config = config; this.http = http; }
    public boolean configured() { return config.configured(); }
    public int lastRetryCount() { return retries.get(); }
    public List<float[]> embedDocuments(List<String> documents) { return embed(documents, config.getDocumentPrefix()); }
    public float[] embedQuery(String query) { return embed(List.of(query), config.getQueryPrefix()).get(0); }

    private List<float[]> embed(List<String> texts, String prefix) {
        retries.set(0);
        if (!configured()) throw new RetrievalDependencyException("embedding_not_configured");
        if (texts.isEmpty() || texts.size() > 32 || texts.stream().anyMatch(x -> x == null || x.isBlank() || x.length() > 16000))
            throw new IllegalArgumentException("embedding 批次为空、过大或文本无效");
        URI uri = safeEndpoint(config.getEndpoint());
        long budget = config.getTimeout().toNanos();
        if (budget <= 0) throw new IllegalArgumentException("embedding 超时必须为正数");
        long started = System.nanoTime();
        try {
            String payload = json.writeValueAsString(Map.of("model", config.getModel(), "input", texts.stream().map(s -> prefix + s).toList()));
            for (int attempt = 0; attempt < 2; attempt++) {
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) throw new RetrievalDependencyException("embedding_timeout");
                retries.set(attempt);
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                        .header("Content-Type", "application/json").header("Authorization", "Bearer " + config.getApiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return parse(response.body(), texts.size());
                if (attempt == 0 && (status == 429 || status >= 500)) continue;
                throw new RetrievalDependencyException("embedding_http_" + status);
            }
            throw new RetrievalDependencyException("embedding_unavailable");
        } catch (RetrievalDependencyException e) { throw e;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RetrievalDependencyException("embedding_interrupted");
        } catch (HttpTimeoutException e) { throw new RetrievalDependencyException("embedding_timeout");
        } catch (Exception e) { throw new RetrievalDependencyException("embedding_invalid_response_or_connection"); }
    }
    private List<float[]> parse(String body, int count) throws Exception {
        if (body.length() > 16_000_000) throw new RetrievalDependencyException("embedding_response_too_large");
        JsonNode data = json.readTree(body).path("data");
        if (!data.isArray() || data.size() != count) throw new RetrievalDependencyException("embedding_batch_mismatch");
        var vectors = new float[count][];
        for (JsonNode item : data) {
            JsonNode index = item.path("index"), values = item.path("embedding");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0 || index.intValue() >= count || vectors[index.intValue()] != null)
                throw new RetrievalDependencyException("embedding_index_invalid");
            if (!values.isArray() || values.size() != config.getDimensions()) throw new RetrievalDependencyException("embedding_dimension_mismatch");
            float[] vector = new float[values.size()]; double norm = 0;
            for (int i = 0; i < vector.length; i++) {
                if (!values.get(i).isNumber()) throw new RetrievalDependencyException("embedding_value_invalid");
                vector[i] = values.get(i).floatValue();
                if (!Float.isFinite(vector[i])) throw new RetrievalDependencyException("embedding_value_invalid");
                norm += (double)vector[i] * vector[i];
            }
            if (norm == 0) throw new RetrievalDependencyException("embedding_zero_vector");
            vectors[index.intValue()] = vector;
        }
        return List.copyOf(Arrays.asList(vectors));
    }
    static URI safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            boolean localHttp = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !("https".equals(uri.getScheme()) || localHttp))
                throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) { throw new RetrievalDependencyException("invalid_dependency_endpoint"); }
    }
}
