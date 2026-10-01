package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class QdrantRecipeVectorIndex implements RecipeVectorIndex {
    private final RecipeRagProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final AtomicReference<String> activeVersion = new AtomicReference<>();

    public QdrantRecipeVectorIndex(RecipeRagProperties properties, HttpClient httpClient, ObjectMapper json) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.json = json;
    }

    @Override
    public void replaceVersion(String indexVersion, List<RecipeChunk> chunks, List<double[]> vectors) {
        if (indexVersion == null || indexVersion.isBlank()) throw new IllegalArgumentException("indexVersion must not be blank");
        if (chunks == null || chunks.isEmpty() || vectors == null || chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("Recipe chunks and vectors must be non-empty and aligned");
        }
        vectors.forEach(this::validateVector);
        ensureCollection();

        ObjectNode request = json.createObjectNode();
        ArrayNode points = request.putArray("points");
        for (int index = 0; index < chunks.size(); index++) {
            RecipeChunk chunk = chunks.get(index);
            ObjectNode point = points.addObject();
            point.put("id", UUID.nameUUIDFromBytes(chunk.chunkId().getBytes(StandardCharsets.UTF_8)).toString());
            ArrayNode vector = point.putArray("vector");
            for (double value : vectors.get(index)) vector.add(value);
            ObjectNode payload = point.putObject("payload");
            payload.put("indexVersion", indexVersion);
            payload.put("chunkId", chunk.chunkId());
            payload.put("parentRecipeId", chunk.parentRecipeId());
            payload.put("contentHash", chunk.contentHash());
            payload.put("sourcePath", chunk.sourcePath());
            payload.put("chunkType", chunk.type().name());
            payload.put("title", extractTitle(chunk.text()));
            payload.put("text", chunk.text());
        }
        send("PUT", collectionUri("/points?wait=true"), request, 200, 201);
        activeVersion.set(indexVersion);
    }

    @Override
    public Mono<List<RecipeHit>> search(RecipeQuery query, double[] vector, int topK) {
        return Mono.fromCallable(() -> searchBlocking(query, vector, topK)).subscribeOn(Schedulers.boundedElastic());
    }

    private List<RecipeHit> searchBlocking(RecipeQuery query, double[] vector, int topK) {
        validateVector(vector);
        if (query == null || query.text().isBlank() || topK <= 0) return List.of();
        String version = activeVersion.get();
        if (version == null) throw new RecipeRagDependencyException("qdrant_active_version_missing");

        ObjectNode request = json.createObjectNode();
        ArrayNode values = request.putArray("vector");
        for (double value : vector) values.add(value);
        request.put("limit", topK);
        request.put("with_payload", true);
        ObjectNode condition = request.putObject("filter").putArray("must").addObject();
        condition.put("key", "indexVersion").putObject("match").put("value", version);

        JsonNode response = send("POST", collectionUri("/points/search"), request, 200);
        List<RecipeHit> hits = new ArrayList<>();
        int rank = 1;
        for (JsonNode result : response.path("result")) {
            JsonNode payload = result.path("payload");
            hits.add(new RecipeHit(
                    payload.path("chunkId").asText(), payload.path("parentRecipeId").asText(),
                    payload.path("title").asText(), RecipeChunk.ChunkType.valueOf(payload.path("chunkType").asText()),
                    payload.path("text").asText(), result.path("score").asDouble(), rank++, "DENSE"));
        }
        return List.copyOf(hits);
    }

    private void ensureCollection() {
        HttpResponse<String> existing = sendRaw("GET", collectionUri(""), null);
        if (existing.statusCode() == 404) {
            ObjectNode request = json.createObjectNode();
            request.putObject("vectors").put("size", dimensions()).put("distance", "Cosine");
            send("PUT", collectionUri(""), request, 200, 201);
            return;
        }
        if (existing.statusCode() < 200 || existing.statusCode() >= 300) {
            throw dependencyError("qdrant_collection_check_failed", existing);
        }
        try {
            JsonNode size = json.readTree(existing.body()).path("result").path("config")
                    .path("params").path("vectors").path("size");
            if (size.isNumber() && size.asInt() != dimensions()) {
                throw new RecipeRagDependencyException("qdrant_dimension_mismatch: expected "
                        + dimensions() + " but was " + size.asInt());
            }
        } catch (IOException error) {
            throw new RecipeRagDependencyException("qdrant_collection_response_invalid", error);
        }
    }

    private JsonNode send(String method, URI uri, JsonNode body, int... acceptedStatuses) {
        HttpResponse<String> response = sendRaw(method, uri, body);
        for (int accepted : acceptedStatuses) {
            if (response.statusCode() == accepted) {
                try {
                    return json.readTree(response.body());
                } catch (IOException error) {
                    throw new RecipeRagDependencyException("qdrant_response_invalid", error);
                }
            }
        }
        throw dependencyError("qdrant_request_failed", response);
    }

    private HttpResponse<String> sendRaw(String method, URI uri, JsonNode body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout()).header("Accept", "application/json");
            if (!properties.getQdrant().getApiKey().isBlank()) request.header("api-key", properties.getQdrant().getApiKey());
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RecipeRagDependencyException("qdrant_request_interrupted", error);
        } catch (IOException error) {
            throw new RecipeRagDependencyException("qdrant_request_failed", error);
        }
    }

    private RecipeRagDependencyException dependencyError(String code, HttpResponse<String> response) {
        return new RecipeRagDependencyException(code + ": HTTP " + response.statusCode());
    }

    private URI collectionUri(String suffix) {
        String base = properties.getQdrant().getBaseUrl().replaceAll("/+$", "");
        return URI.create(base + "/collections/" + properties.getQdrant().getCollection() + suffix);
    }

    private Duration timeout() {
        Duration configured = properties.getQdrant().getTimeout();
        return configured == null ? Duration.ofSeconds(3) : configured;
    }

    private int dimensions() { return properties.getEmbedding().getDimensions(); }

    private void validateVector(double[] vector) {
        if (vector == null || vector.length != dimensions()) {
            throw new RecipeRagDependencyException("embedding_dimension_mismatch: expected "
                    + dimensions() + " but was " + (vector == null ? 0 : vector.length));
        }
    }

    private String extractTitle(String text) {
        String firstLine = text == null ? "" : text.lines().findFirst().orElse("");
        return firstLine.startsWith("菜名：") ? firstLine.substring("菜名：".length()).trim() : "";
    }
}
