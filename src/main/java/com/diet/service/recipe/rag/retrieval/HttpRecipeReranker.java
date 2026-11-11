package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Batched text Cross-Encoder API adapter (index/relevance_score contract).
 * Scores every supplied parent once, validates the complete response, then admits evidence.
 * No retries and no fail-open fallback: provider failure is not evidence irrelevance.
 */
public final class HttpRecipeReranker implements RecipeReranker {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int candidateTopK;
    private final int topN;
    private final int maxDocumentChars;
    private final Double threshold;
    private final HttpClient client;
    private final ObjectMapper json;

    public HttpRecipeReranker(RecipeRagProperties.Rerank config, HttpClient client, ObjectMapper json) {
        config.validate();
        this.endpoint = URI.create(config.getEndpoint());
        this.apiKey = config.getApiKey();
        this.model = config.getModel();
        this.timeout = config.getTimeout();
        this.candidateTopK = config.getCandidateTopK();
        this.topN = config.getTopN();
        this.maxDocumentChars = config.getMaxDocumentChars();
        this.threshold = config.isFilterEnabled() ? config.getMinScore() : null;
        this.client = client;
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public Mono<List<RetrievedRecipe>> rerank(RecipeQuery query, List<RetrievedRecipe> recipes) {
        return rerankDetailed(query, recipes).flatMap(result ->
                result.status() == RecipeRerankResult.Status.UNAVAILABLE
                        ? Mono.error(new RecipeRagDependencyException("recipe_rerank_unavailable"))
                        : Mono.just(result.recipes()));
    }

    @Override
    public Mono<RecipeRerankResult> rerankDetailed(RecipeQuery query, List<RetrievedRecipe> recipes) {
        return Mono.defer(() -> {
            long started = System.nanoTime();
            List<RetrievedRecipe> input = List.copyOf(recipes.stream().limit(candidateTopK).toList());
            List<String> texts = input.stream().map(this::render).toList();
            List<String> hashes = texts.stream().map(HttpRecipeReranker::sha256).toList();
            String queryHash = sha256(query.text());
            if (input.isEmpty()) return Mono.just(outcome(RecipeRerankResult.Status.FILTERED,
                    List.of(), List.of(), input.size(), started, "", queryHash, hashes));
            if (query.text().isBlank() || input.stream().map(value -> value.recipe().recipeId()).distinct().count() != input.size())
                return Mono.just(outcome(RecipeRerankResult.Status.UNAVAILABLE,
                        List.of(), List.of(), input.size(), started, "invalid_input", queryHash, hashes));

            return Mono.fromCallable(() -> request(query.text(), texts))
                    .flatMap(request -> Mono.fromFuture(client.sendAsync(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))))
                    .map(response -> {
                        if (response.statusCode() != 200) throw new ScoringFailure("http_" + response.statusCode());
                        return parse(response.body(), input);
                    })
                    .timeout(timeout)
                    .map(scores -> {
                        Map<String, RetrievedRecipe> byId = new java.util.HashMap<>();
                        input.forEach(recipe -> byId.put(recipe.recipe().recipeId(), recipe));
                        List<RetrievedRecipe> accepted = scores.stream()
                                .filter(score -> threshold == null || score.relevanceScore() >= threshold)
                                .limit(topN).map(score -> byId.get(score.recipeId())).toList();
                        return outcome(accepted.isEmpty() ? RecipeRerankResult.Status.FILTERED : RecipeRerankResult.Status.ACCEPTED,
                                accepted, scores, input.size(), started, "", queryHash, hashes);
                    })
                    .onErrorResume(error -> Mono.just(outcome(RecipeRerankResult.Status.UNAVAILABLE,
                            List.of(), List.of(), input.size(), started, errorCode(error), queryHash, hashes)));
        });
    }

    private HttpRequest request(String query, List<String> documents) throws Exception {
        String body = json.writeValueAsString(Map.of("model", model, "query", query,
                "documents", documents, "top_n", documents.size(), "return_documents", false));
        return HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private List<RecipeRerankResult.CandidateScore> parse(String body, List<RetrievedRecipe> input) {
        try (JsonParser parser = json.createParser(body)) {
            if (body.length() > 2_000_000) throw new ScoringFailure("invalid_response");
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) throw new ScoringFailure("invalid_response");
            JsonNode results = root.path("results");
            if (!results.isArray() || results.size() != input.size()) throw new ScoringFailure("invalid_response");
            Set<Integer> seen = new HashSet<>();
            List<IndexedScore> values = new ArrayList<>();
            for (JsonNode result : results) {
                JsonNode index = result.path("index");
                JsonNode score = result.path("relevance_score");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                        || index.intValue() >= input.size() || !seen.add(index.intValue())
                        || !score.isNumber() || !Double.isFinite(score.doubleValue())) throw new ScoringFailure("invalid_response");
                values.add(new IndexedScore(index.intValue(), score.doubleValue()));
            }
            values.sort(Comparator.comparingDouble(IndexedScore::score).reversed().thenComparingInt(IndexedScore::index));
            return values.stream().map(value -> {
                RetrievedRecipe recipe = input.get(value.index());
                return new RecipeRerankResult.CandidateScore(recipe.recipe().recipeId(), recipe.score(), value.score());
            }).toList();
        } catch (Exception invalid) {
            throw new ScoringFailure("invalid_response");
        }
    }

    private String render(RetrievedRecipe candidate) {
        var recipe = candidate.recipe();
        String value = "菜名：" + recipe.title() + "\n别名：" + String.join("、", recipe.aliases())
                + "\n食材：" + String.join("；", recipe.ingredients()) + "\n步骤：" + String.join("；", recipe.steps());
        return value.length() <= maxDocumentChars ? value : value.substring(0, maxDocumentChars);
    }

    private RecipeRerankResult outcome(RecipeRerankResult.Status status, List<RetrievedRecipe> accepted,
                                      List<RecipeRerankResult.CandidateScore> scores, int count, long started,
                                      String errorCode, String queryHash, List<String> hashes) {
        return new RecipeRerankResult(status, accepted, scores, model, threshold, count,
                (System.nanoTime() - started) / 1_000_000, errorCode, queryHash, hashes);
    }

    private String errorCode(Throwable error) {
        for (int depth = 0; error != null && depth < 10; depth++, error = error.getCause()) {
            if (error instanceof ScoringFailure failure) return failure.code;
            if (error instanceof TimeoutException || error instanceof HttpTimeoutException) return "timeout";
        }
        return "transport_error";
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private record IndexedScore(int index, double score) { }
    private static final class ScoringFailure extends RuntimeException {
        private final String code;
        private ScoringFailure(String code) { super(code); this.code = code; }
    }
}
