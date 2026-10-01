package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantRecipeVectorIndexTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private volatile Integer existingCollectionDimensions;
    private volatile boolean failVersionTwoUpsert;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/collections/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsCosineCollectionAndSearchesOnlyTheActiveVersion() {
        QdrantRecipeVectorIndex index = new QdrantRecipeVectorIndex(
                properties(3), HttpClient.newHttpClient(), new ObjectMapper());
        RecipeChunk chunk = new RecipeChunk("chunk-a", "recipe-a", RecipeChunk.ChunkType.STEPS,
                0, "菜名：西红柿炒鸡蛋\n先炒鸡蛋", "hash-a");

        index.replaceVersion("corpus-v1", List.of(chunk), List.of(new double[]{1, 0, 0}));
        var hits = index.search(new RecipeQuery("西红柿炒鸡蛋", List.of(), List.of(), List.of(), null),
                new double[]{1, 0, 0}, 5).block();

        assertThat(requests).anySatisfy(request -> assertThat(request)
                .contains("PUT /collections/diet_recipe_chunks_bge_m3_1024", "\"size\":3", "\"distance\":\"Cosine\""));
        assertThat(requests).anySatisfy(request -> assertThat(request)
                .contains("POST /collections/diet_recipe_chunks_bge_m3_1024/points/search", "indexVersion", "corpus-v1"));
        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.chunkId()).isEqualTo("chunk-a");
            assertThat(hit.parentRecipeId()).isEqualTo("recipe-a");
            assertThat(hit.channel()).isEqualTo("DENSE");
        });
    }

    @Test
    void rejectsWrongVectorDimensionBeforeCallingQdrant() {
        QdrantRecipeVectorIndex index = new QdrantRecipeVectorIndex(
                properties(3), HttpClient.newHttpClient(), new ObjectMapper());
        RecipeChunk chunk = new RecipeChunk("chunk-a", "recipe-a", RecipeChunk.ChunkType.STEPS,
                0, "text", "hash-a");

        assertThatThrownBy(() -> index.replaceVersion(
                "corpus-v1", List.of(chunk), List.of(new double[]{1, 0})))
                .isInstanceOf(RecipeRagDependencyException.class)
                .hasMessageContaining("embedding_dimension_mismatch");
        assertThat(requests).isEmpty();
    }

    @Test
    void rejectsExistingCollectionWithAnotherDimension() {
        existingCollectionDimensions = 2;
        QdrantRecipeVectorIndex index = new QdrantRecipeVectorIndex(
                properties(3), HttpClient.newHttpClient(), new ObjectMapper());
        RecipeChunk chunk = new RecipeChunk("chunk-a", "recipe-a", RecipeChunk.ChunkType.STEPS,
                0, "text", "hash-a");

        assertThatThrownBy(() -> index.replaceVersion(
                "corpus-v1", List.of(chunk), List.of(new double[]{1, 0, 0})))
                .isInstanceOf(RecipeRagDependencyException.class)
                .hasMessageContaining("qdrant_dimension_mismatch");
    }

    @Test
    void failedReplacementKeepsThePreviousVersionActive() {
        QdrantRecipeVectorIndex index = new QdrantRecipeVectorIndex(
                properties(3), HttpClient.newHttpClient(), new ObjectMapper());
        RecipeChunk chunk = new RecipeChunk("chunk-a", "recipe-a", RecipeChunk.ChunkType.STEPS,
                0, "text", "hash-a");
        index.replaceVersion("corpus-v1", List.of(chunk), List.of(new double[]{1, 0, 0}));
        failVersionTwoUpsert = true;

        assertThatThrownBy(() -> index.replaceVersion(
                "corpus-v2", List.of(chunk), List.of(new double[]{1, 0, 0})))
                .isInstanceOf(RecipeRagDependencyException.class);
        requests.clear();
        index.search(new RecipeQuery("西红柿炒鸡蛋", List.of(), List.of(), List.of(), null),
                new double[]{1, 0, 0}, 5).block();

        assertThat(requests).singleElement().asString().contains("corpus-v1").doesNotContain("corpus-v2");
    }

    private RecipeRagProperties properties(int dimensions) {
        RecipeRagProperties properties = new RecipeRagProperties();
        properties.getQdrant().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.getQdrant().setCollection("diet_recipe_chunks_bge_m3_1024");
        properties.getEmbedding().setDimensions(dimensions);
        return properties;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body);
        String response;
        int status;
        if (exchange.getRequestMethod().equals("GET")) {
            if (existingCollectionDimensions == null) {
                response = "{\"status\":\"not found\"}";
                status = 404;
            } else {
                response = "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":"
                        + existingCollectionDimensions + "}}}}}";
                status = 200;
            }
        } else if (exchange.getRequestMethod().equals("POST")) {
            response = "{\"result\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"score\":0.91,"
                    + "\"payload\":{\"chunkId\":\"chunk-a\",\"parentRecipeId\":\"recipe-a\","
                    + "\"title\":\"西红柿炒鸡蛋\",\"chunkType\":\"STEPS\",\"text\":\"先炒鸡蛋\"}}]}";
            status = 200;
        } else if (failVersionTwoUpsert && exchange.getRequestURI().getPath().endsWith("/points")
                && body.contains("corpus-v2")) {
            response = "{\"status\":\"failed\"}";
            status = 500;
        } else {
            response = "{\"result\":true,\"status\":\"ok\"}";
            status = 200;
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
