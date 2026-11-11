package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import com.diet.service.recipe.rag.index.AgentScopeRecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.LuceneRecipeSparseIndex;
import com.diet.service.recipe.rag.index.QdrantRecipeVectorIndex;
import com.diet.service.recipe.rag.index.RecipeIndexCoordinator;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.retrieval.HttpRecipeReranker;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeParentResolver;
import com.diet.service.recipe.rag.retrieval.RecipeRerankResult;
import com.diet.service.recipe.rag.retrieval.RecipeRetrievalMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.model.ExecutionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in. Ordinary mvn test never loads a secret or contacts any evaluation dependency. */
class RecipeRerankEvaluationTest {
    private static final String COMMIT = "a0b14bd388ee996943173679c3a7c9db7da04a6c";
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    @TempDir Path temporaryIndex;

    @Test
    void comparesSharedCandidatesAndWritesCheckpoints() throws Exception {
        assumeTrue(Boolean.getBoolean("diet.recipe-rerank.live"), "Explicitly enable recipe rerank evaluation");
        String split = System.getProperty("diet.recipe-rerank.split", "dev");
        Double threshold = threshold();
        String calibrationId = System.getProperty("diet.recipe-rerank.calibration-run-id", "");
        List<RecipeEvaluationDataset.Example> all = RecipeRerankEvaluationReport.selectExamples(split,
                Boolean.getBoolean("diet.recipe-rerank.frozen"), calibrationId, threshold);
        int limit = Integer.getInteger("diet.recipe-rerank.limit", all.size());
        int repeats = Integer.getInteger("diet.recipe-rerank.repeats", 1);
        if (limit < 1 || repeats < 1 || repeats > 10) throw new IllegalArgumentException("Invalid evaluation limit/repeats");
        var examples = all.stream().limit(limit).toList();

        var properties = properties();
        var rerank = properties.getRerank();
        // Obtain the entire score list in one API call. Arms differ only in local ordering/filtering.
        rerank.setFilterEnabled(false);
        rerank.setTopN(rerank.getCandidateTopK());
        rerank.validate();
        var scorer = new HttpRecipeReranker(rerank,
                HttpClient.newBuilder().connectTimeout(rerank.getTimeout()).build(), json);
        Path corpusRoot = Path.of(required("diet.recipe-rag.corpus-path"));
        var importer = new RecipeCorpusImporter(new HowToCookMarkdownParser());
        var corpus = importer.importFrom(corpusRoot, COMMIT);
        var sparse = new LuceneRecipeSparseIndex(temporaryIndex.resolve("lucene"));
        var embedding = new AgentScopeRecipeEmbeddingModel(OllamaTextEmbedding.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl()).modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimensions())
                .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(10)).maxAttempts(1).build()).build());
        var vector = new QdrantRecipeVectorIndex(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json);
        var build = new RecipeIndexCoordinator(importer, sparse, embedding, vector, properties, "howtocook-v1").rebuild(corpusRoot);
        var retriever = new HybridRecipeRetriever(sparse, embedding, vector, new RecipeParentResolver(corpus.documents()),
                Duration.ofSeconds(10), 20, 20, rerank.getCandidateTopK(), 60);
        String runId = runId();
        Path report = output(runId);
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", runId);
        metadata.put("reportSchemaVersion", 2);
        metadata.put("split", split);
        metadata.put("datasetHash", RecipeEvaluationDataset.load("v2").fingerprint());
        metadata.put("corpusCommit", COMMIT);
        metadata.put("corpusHash", corpus.manifest().corpusHash());
        metadata.put("corpusDocumentCount", corpus.documents().size());
        metadata.put("embeddingModel", embedding.modelName());
        metadata.put("embeddingDimensions", embedding.dimensions());
        metadata.put("rerankModel", rerank.getModel());
        metadata.put("maxDocumentChars", rerank.getMaxDocumentChars());
        metadata.put("candidateTopK", rerank.getCandidateTopK());
        metadata.put("evaluationTopK", 10);
        metadata.put("productionTopN", 3);
        metadata.put("repeats", repeats);
        metadata.put("queryIds", examples.stream().map(RecipeEvaluationDataset.Example::queryId).toList());
        metadata.put("plannedObservations", examples.size() * repeats);
        metadata.put("limitedRun", examples.size() != all.size());
        metadata.put("calibrationRunId", calibrationId);
        metadata.put("frozenThreshold", threshold == null ? "unset" : threshold);
        metadata.put("scoreTimeoutMs", rerank.getTimeout().toMillis());
        metadata.put("applicationRevision", revision());
        metadata.put("protocol", "text-rerank/index/relevance_score; all scores requested; no provider response text used");
        List<RecipeRerankEvaluationReport.Observation> observations = new ArrayList<>();
        write(report, RecipeRerankEvaluationReport.build(metadata, observations, threshold));
        int consecutiveFailures = 0;
        try {
            evaluation:
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (var example : examples) {
                    long started = System.nanoTime();
                    RecipeRerankEvaluationReport.Observation row;
                    try {
                        var query = new RecipeQuery(example.query(), example.requiredIngredients(), example.excludedIngredients(),
                                example.requiredTools(), example.maxMinutes());
                        var retrieval = retriever.retrieve(query, RecipeRetrievalMode.PARALLEL_HYBRID).block(Duration.ofSeconds(15));
                        if (retrieval == null) throw new IllegalStateException("Missing retrieval signal");
                        long retrievalMs = elapsed(started);
                        var scored = scorer.rerankDetailed(query, retrieval.recipes()).block();
                        if (scored == null) throw new IllegalStateException("Missing score signal");
                        row = new RecipeRerankEvaluationReport.Observation(example.queryId(), repeat,
                                retrieval.recipes().stream().map(value -> value.recipe().recipeId()).toList(), retrievalMs,
                                false, scored.status(), scored.candidates(), scored.durationMs(), scored.errorCode(),
                                scored.queryHash(), scored.documentHashes(), retrieval.diagnostics().actualMode().name(),
                                // Keep branch identity but never serialize arbitrary dependency exception messages.
                                retrieval.diagnostics().degradationReasons().stream()
                                        .map(reason -> reason.startsWith("dense_failed:") ? "dense_failed" : "sparse_failed").toList());
                    } catch (RuntimeException failure) {
                        row = new RecipeRerankEvaluationReport.Observation(example.queryId(), repeat, List.of(), elapsed(started),
                                true, RecipeRerankResult.Status.UNAVAILABLE, List.of(), 0, "retrieval_or_scoring_error", "", List.of());
                    }
                    observations.add(row);
                    write(report, RecipeRerankEvaluationReport.build(metadata, observations, threshold));
                    consecutiveFailures = row.paired() ? 0 : consecutiveFailures + 1;
                    if (Set.of("http_401", "http_403", "http_404", "http_451").contains(row.errorCode()) || consecutiveFailures >= 3)
                        break evaluation;
                }
            }
        } finally {
            // The collection is evaluation-only; never point this command at the production collection.
            vector.activateVersion("evaluation-finished");
            vector.discardVersion(build.indexVersion());
        }
        System.out.println("Recipe relevance report: " + report.toAbsolutePath());
    }

    @Test
    void replaysSavedScoresWithoutAnyModelOrDatabaseCalls() throws Exception {
        String source = System.getProperty("diet.recipe-rerank.replay");
        assumeTrue(source != null && !source.isBlank(), "Set replay report path for offline threshold comparison");
        Path report = replay(Path.of(source), threshold(), Boolean.getBoolean("diet.recipe-rerank.frozen"),
                System.getProperty("diet.recipe-rerank.calibration-run-id", ""));
        System.out.println("Recipe relevance replay: " + report.toAbsolutePath());
    }

    Path replay(Path source, Double threshold, boolean frozen, String calibrationId) throws Exception {
        String sourceText = Files.readString(source);
        var root = json.readTree(sourceText);
        var metadata = new LinkedHashMap<>(json.convertValue(root.path("metadata"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }));
        if (!(metadata.get("reportSchemaVersion") instanceof Number schema) || schema.intValue() != 2)
            throw new IllegalArgumentException("Replay requires schema 2 with retrieval health diagnostics");
        if (!RecipeEvaluationDataset.load("v2").fingerprint().equals(metadata.get("datasetHash")))
            throw new IllegalArgumentException("Replay dataset fingerprint mismatch");
        String split = String.valueOf(metadata.get("split"));
        if (threshold == null) throw new IllegalArgumentException("Replay requires explicit min-score");
        if ("test".equals(split) && (!(metadata.get("frozenThreshold") instanceof Number number)
                || Double.compare(number.doubleValue(), threshold) != 0))
            throw new IllegalArgumentException("Cannot tune thresholds on test replay");
        var allowed = RecipeRerankEvaluationReport.selectExamples(split, frozen, calibrationId, threshold);
        Set<String> allowedIds = new HashSet<>(allowed.stream().map(RecipeEvaluationDataset.Example::queryId).toList());
        Set<String> pairs = new HashSet<>();
        List<RecipeRerankEvaluationReport.Observation> rows = new ArrayList<>();
        for (var value : root.path("rows")) {
            var row = json.treeToValue(value, RecipeRerankEvaluationReport.Observation.class);
            if (!allowedIds.contains(row.queryId()) || row.repeat() < 1 || !pairs.add(row.queryId() + "/" + row.repeat()))
                throw new IllegalArgumentException("Replay sample identity is invalid");
            if (row.paired()) {
                var ids = row.scores().stream().map(RecipeRerankResult.CandidateScore::recipeId).toList();
                if (new HashSet<>(ids).size() != ids.size() || !new HashSet<>(ids).equals(new HashSet<>(row.candidates()))
                        || row.scores().stream().anyMatch(score -> !Double.isFinite(score.relevanceScore())))
                    throw new IllegalArgumentException("Replay scores are incomplete or invalid");
            }
            rows.add(row);
        }
        metadata.put("sourceReportSha256", HttpRecipeReranker.sha256(sourceText));
        metadata.put("sourceRunId", metadata.get("runId"));
        metadata.put("runId", runId());
        metadata.put("mode", "offline_score_replay_no_new_latency_measurement");
        Path output = output(String.valueOf(metadata.get("runId")));
        write(output, RecipeRerankEvaluationReport.build(metadata, rows, threshold));
        return output;
    }

    private RecipeRagProperties properties() {
        var properties = new RecipeRagProperties();
        properties.getRerank().setEndpoint(value("diet.recipe-rag.rerank.endpoint", "RECIPE_RERANK_ENDPOINT", "https://api.siliconflow.cn/v1/rerank"));
        properties.getRerank().setApiKey(value("diet.recipe-rag.rerank.api-key", "RECIPE_RERANK_API_KEY", ""));
        properties.getRerank().setModel(value("diet.recipe-rag.rerank.model", "RECIPE_RERANK_MODEL", "BAAI/bge-reranker-v2-m3"));
        properties.getRerank().setCandidateTopK(Integer.getInteger("diet.recipe-rerank.candidate-top-k", 20));
        properties.getRerank().setMaxDocumentChars(Integer.getInteger("diet.recipe-rerank.max-document-chars", 3000));
        properties.getRerank().setTimeout(Duration.ofSeconds(Integer.getInteger("diet.recipe-rerank.timeout-seconds", 5)));
        properties.getEmbedding().setBaseUrl(System.getProperty("diet.recipe-rag.embedding.base-url", "http://localhost:11434"));
        properties.getQdrant().setBaseUrl(System.getProperty("diet.recipe-rag.qdrant.base-url", "http://localhost:16333"));
        String collection = System.getProperty("diet.recipe-rerank.eval-collection", "diet_recipe_relevance_eval");
        if (!collection.endsWith("_eval")) throw new IllegalArgumentException("Evaluation collection must end with _eval");
        properties.getQdrant().setCollection(collection);
        properties.getQdrant().setApiKey(System.getenv().getOrDefault("QDRANT_API_KEY", ""));
        return properties;
    }

    private Double threshold() {
        String value = System.getProperty("diet.recipe-rerank.min-score");
        if (value == null || value.isBlank()) return null;
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("min-score must be finite");
        return result;
    }

    private String value(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) value = System.getenv(environment);
        return value == null || value.isBlank() ? fallback : value;
    }
    private String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing property " + property);
        return value;
    }
    private String runId() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + "-" + UUID.randomUUID().toString().substring(0, 8); }
    private Path output(String runId) { return Path.of("target/evaluation/recipe/relevance", runId, "report.json"); }
    private void write(Path path, Object report) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        json.writeValue(path.toFile(), report);
    }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private String revision() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception unavailable) { return "unavailable"; }
    }
}
