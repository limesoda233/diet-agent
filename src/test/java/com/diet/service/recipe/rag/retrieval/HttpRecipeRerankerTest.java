package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRecipeRerankerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer server;
    private ExecutorService executor;
    private RecipeRagProperties.Rerank config;
    private volatile int status = 200;
    private volatile String body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}";
    private volatile long delayMs;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/rerank", exchange -> {
            calls.incrementAndGet();
            request.set(json.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMs);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException cancelled) {
                // The timeout test intentionally closes the HTTP request before this reply.
            } finally {
                exchange.close();
            }
        });
        server.start();
        config = new RecipeRagProperties.Rerank();
        config.setEnabled(true);
        config.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/rerank");
        config.setApiKey("fixture-secret");
        config.setMinScore(0.5);
        config.setTimeout(Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void scoresAllCandidatesMapsIndicesAndFiltersWithoutOverwritingRrfScore() {
        var result = reranker().rerankDetailed(query(), candidates()).block();

        assertThat(result.status()).isEqualTo(RecipeRerankResult.Status.ACCEPTED);
        assertThat(result.recipes()).extracting(value -> value.recipe().recipeId()).containsExactly("b");
        assertThat(result.recipes().get(0).score()).isEqualTo(0.018);
        assertThat(result.candidates()).extracting(RecipeRerankResult.CandidateScore::recipeId).containsExactly("b", "a");
        assertThat(result.candidates().get(0).relevanceScore()).isEqualTo(0.9);
        assertThat(request.get().path("query").asText()).isEqualTo(query().text());
        assertThat(request.get().path("top_n").asInt()).isEqualTo(2);
        assertThat(request.get().path("return_documents").asBoolean()).isFalse();
        assertThat(request.get().path("documents").get(0).asText()).contains("菜名：普通包子", "食材：面粉", "步骤：");
        assertThat(result.documentHashes()).hasSize(2).allMatch(value -> value.matches("[a-f0-9]{64}"));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rerankOnlyDoesNotApplyThresholdAndUsesStableOriginalOrderForTies() {
        config.setFilterEnabled(false);
        config.setMinScore(null);
        body = "{\"results\":[{\"index\":1,\"relevance_score\":0.2},{\"index\":0,\"relevance_score\":0.2}]}";
        var result = reranker().rerankDetailed(query(), candidates()).block();
        assertThat(result.recipes()).extracting(value -> value.recipe().recipeId()).containsExactly("a", "b");
    }

    @Test
    void thresholdIsInclusiveAndAllBelowThresholdReturnsFilteredInsteadOfUnavailable() {
        config.setMinScore(0.9);
        assertThat(reranker().rerankDetailed(query(), candidates()).block().recipes()).hasSize(1);
        config.setMinScore(0.91);
        var result = reranker().rerankDetailed(query(), candidates()).block();
        assertThat(result.status()).isEqualTo(RecipeRerankResult.Status.FILTERED);
        assertThat(result.recipes()).isEmpty();
        assertThat(result.errorCode()).isEmpty();
        assertThat(result.candidates()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"results\":[]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}",
            "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}",
            "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}",
            "{\"results\":[{\"index\":1.2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.8}]}",
            "{\"results\":[{\"index\":1,\"relevance_score\":\"0.9\"},{\"index\":0,\"relevance_score\":0.8}]}",
            "{\"results\":[{\"index\":1,\"relevance_score\":1e999},{\"index\":0,\"relevance_score\":0.8}]}",
            "{\"results\":[],\"results\":[]}", "{} {}", "fixture-secret"
    })
    void rejectsMalformedIncompleteOrUntrustedScoringResponses(String response) {
        body = response;
        var result = reranker().rerankDetailed(query(), candidates()).block();
        assertThat(result.status()).isEqualTo(RecipeRerankResult.Status.UNAVAILABLE);
        assertThat(result.recipes()).isEmpty();
        assertThat(result.errorCode()).isEqualTo("invalid_response");
        assertThat(result.toString()).doesNotContain("fixture-secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500})
    void reportsOnlyHttpStatusAndNeverFallsBackToUnfilteredCandidates(int responseStatus) {
        status = responseStatus;
        body = "fixture-secret with upstream diagnostic body";
        var result = reranker().rerankDetailed(query(), candidates()).block();
        assertThat(result.status()).isEqualTo(RecipeRerankResult.Status.UNAVAILABLE);
        assertThat(result.errorCode()).isEqualTo("http_" + responseStatus);
        assertThat(result.recipes()).isEmpty();
        assertThat(result.toString()).doesNotContain("fixture-secret", "upstream diagnostic body");
        assertThat(calls).hasValue(1);
    }

    @Test
    void totalTimeoutDoesNotRetryOrAcceptOriginalCandidates() {
        delayMs = 400;
        config.setTimeout(Duration.ofMillis(80));
        var result = reranker().rerankDetailed(query(), candidates()).block(Duration.ofSeconds(2));
        assertThat(result.status()).isEqualTo(RecipeRerankResult.Status.UNAVAILABLE);
        assertThat(result.errorCode()).isEqualTo("timeout");
        assertThat(result.recipes()).isEmpty();
    }

    @Test
    void doesNotCallProviderForEmptyCandidates() {
        var result = reranker().rerankDetailed(query(), List.of()).block();
        assertThat(result.status()).isEqualTo(RecipeRerankResult.Status.FILTERED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void candidateAndTextBudgetsAreEnforcedBeforeRequest() {
        config.setCandidateTopK(1);
        config.setTopN(1);
        config.setMaxDocumentChars(512);
        body = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
        var large = new RecipeDocument("long", "长菜谱", List.of(), List.of("鸡肉"),
                List.of("炒制".repeat(1000)), "", Map.of(), "long.md", "commit", "hash");
        var result = reranker().rerankDetailed(query(), List.of(new RetrievedRecipe(large, 0.01, List.of()), candidates().get(0))).block();
        assertThat(request.get().path("documents")).hasSize(1);
        assertThat(request.get().path("documents").get(0).asText()).hasSizeLessThanOrEqualTo(512);
        assertThat(result.inputCount()).isEqualTo(1);
    }

    private HttpRecipeReranker reranker() {
        return new HttpRecipeReranker(config, HttpClient.newHttpClient(), json);
    }

    private RecipeQuery query() {
        return new RecipeQuery("新疆烤包子怎么做", List.of(), List.of(), List.of(), null);
    }

    private List<RetrievedRecipe> candidates() {
        return List.of(recipe("a", "普通包子", 0.032), recipe("b", "新疆烤包子", 0.018));
    }

    private RetrievedRecipe recipe(String id, String name, double rrf) {
        return new RetrievedRecipe(new RecipeDocument(id, name, List.of(), List.of("面粉"),
                List.of("制作"), "", Map.of(), id + ".md", "commit", "hash"), rrf, List.of());
    }
}

