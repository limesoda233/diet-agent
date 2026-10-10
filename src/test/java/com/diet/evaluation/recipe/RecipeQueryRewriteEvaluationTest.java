package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import com.diet.service.recipe.rag.index.AgentScopeRecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.LuceneRecipeSparseIndex;
import com.diet.service.recipe.rag.index.QdrantRecipeVectorIndex;
import com.diet.service.recipe.rag.index.RecipeIndexCoordinator;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.retrieval.AgentScopeRecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeParentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit, development-split-only comparison. It never changes or tunes locked-test qrels. */
class RecipeQueryRewriteEvaluationTest {
    private static final String COMMIT = "a0b14bd388ee996943173679c3a7c9db7da04a6c";
    private static final List<String> SLICE_ORDER = List.of(
            "noisy_query", "alias", "multi_condition", "constraints", "ingredient_reverse",
            "tools_time", "technique", "near_ood", "answer_grounding", "exact_name");

    @TempDir Path temporaryIndex;

    @Test
    void comparesOriginalAndRewrittenQueriesOnDevelopmentSplit() throws Exception {
        assumeTrue(Boolean.getBoolean("diet.recipe-rewrite.live"),
                "Set -Ddiet.recipe-rewrite.live=true with HowToCook, Ollama, Qdrant and a light chat model");
        Path corpusRoot = Path.of(requiredProperty("diet.recipe-rag.corpus-path"));
        String apiKey = firstNonBlank(System.getProperty("agentscope.deepseek.api-key"),
                System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null) throw new IllegalArgumentException(
                "Configure agentscope.deepseek.api-key or DEEPSEEK_API_KEY locally");

        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        RecipeRagProperties properties = properties();
        var importer = new RecipeCorpusImporter(new HowToCookMarkdownParser());
        var corpus = importer.importFrom(corpusRoot, COMMIT);
        var sparse = new LuceneRecipeSparseIndex(temporaryIndex.resolve("lucene"));
        var embedding = new AgentScopeRecipeEmbeddingModel(OllamaTextEmbedding.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimensions())
                .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(10)).maxAttempts(1).build())
                .build());
        var vector = new QdrantRecipeVectorIndex(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), json);
        new RecipeIndexCoordinator(importer, sparse, embedding, vector, properties, "howtocook-v1")
                .rebuild(corpusRoot);
        var retriever = new HybridRecipeRetriever(sparse, embedding, vector,
                new RecipeParentResolver(corpus.documents()), Duration.ofSeconds(10), 20, 20, 10, 60);
        Model lightModel = OpenAIChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(firstNonBlank(System.getProperty("agentscope.deepseek.base-url"),
                        System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"))
                .modelName(firstNonBlank(System.getProperty("diet.llm.light-model"),
                        System.getenv("DEEPSEEK_LIGHT_MODEL"), "deepseek-chat"))
                .build();
        Duration rewriteTimeout = Duration.ofSeconds(
                Integer.getInteger("diet.recipe-rewrite-evaluation.timeout-seconds", 10));
        var rewriter = new AgentScopeRecipeQueryRewriter(lightModel, json, rewriteTimeout, null);

        RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load();
        int limit = Integer.getInteger("diet.recipe-rewrite-evaluation.limit", 12);
        List<RecipeEvaluationDataset.Example> examples = selectDevelopmentExamples(dataset, limit);
        Map<String, RecipeEvaluationRunner.QueryResult> baseline = new LinkedHashMap<>();
        Map<String, RecipeEvaluationRunner.QueryResult> rewritten = new LinkedHashMap<>();
        List<CaseComparison> cases = new ArrayList<>();
        for (RecipeEvaluationDataset.Example example : examples) {
            RecipeQuery original = query(example);
            baseline.put(example.queryId(), retrieve(retriever, example, original, 0));
            long rewriteStartedAt = System.nanoTime();
            var rewriteOutcome = rewriter.rewriteDetailed(original).block();
            if (rewriteOutcome == null) throw new IllegalStateException("rewrite returned no signal");
            RecipeQuery effective = rewriteOutcome.query();
            long rewriteMs = elapsedMs(rewriteStartedAt);
            RecipeEvaluationRunner.QueryResult rewrittenResult = retrieve(
                    retriever, example, effective == null ? original : effective, rewriteMs);
            rewritten.put(example.queryId(), rewrittenResult);
            cases.add(new CaseComparison(
                    example.queryId(), example.slice(), original.text(),
                    effective == null ? original.text() : effective.text(),
                    effective != null && !effective.text().equals(original.text()),
                    rewriteOutcome.status(), rewriteOutcome.errorType(),
                    rewriteMs,
                    baseline.get(example.queryId()).rankedParentIds(),
                    rewrittenResult.rankedParentIds(),
                    example.relevantRecipeIds()));
        }

        RecipeEvaluationRunner runner = new RecipeEvaluationRunner();
        var baselineMetrics = runner.run(RecipeExperiment.PARALLEL_HYBRID, examples,
                (ignored, example) -> baseline.get(example.queryId()));
        var rewriteMetrics = runner.run(RecipeExperiment.PARALLEL_HYBRID, examples,
                (ignored, example) -> rewritten.get(example.queryId()));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("datasetVersion", dataset.version());
        report.put("datasetHash", dataset.fingerprint());
        report.put("split", "dev");
        report.put("sampleCount", examples.size());
        report.put("changedQueryCount", cases.stream().filter(CaseComparison::changed).count());
        long fallbackCount = cases.stream().filter(value -> "FALLBACK".equals(value.rewriteStatus())).count();
        report.put("fallbackCount", fallbackCount);
        report.put("status", fallbackCount == cases.size() ? "failed" : fallbackCount > 0 ? "partial" : "complete");
        report.put("rewriteModel", lightModel.getModelName());
        report.put("embeddingModel", embedding.modelName());
        report.put("latencyNote", "rewrite latency is included only in the rewrite arm; this single pass does not control cache warmup");
        report.put("baseline", baselineMetrics);
        report.put("rewrite", rewriteMetrics);
        report.put("cases", cases);
        Path target = Path.of("target/evaluation/recipe/rewrite-dev-report.json");
        Files.createDirectories(target.toAbsolutePath().getParent());
        json.writeValue(target.toFile(), report);
        if (fallbackCount == cases.size()) {
            throw new IllegalStateException("All query rewrites fell back; inspect rewrite-dev-report.json");
        }
    }

    private RecipeEvaluationRunner.QueryResult retrieve(HybridRecipeRetriever retriever,
                                                         RecipeEvaluationDataset.Example example,
                                                         RecipeQuery query,
                                                         long additionalLatencyMs) {
        var result = retriever.retrieve(query, RecipeExperiment.PARALLEL_HYBRID.mode()).block();
        if (result == null) throw new IllegalStateException("recipe retrieval returned no signal");
        return new RecipeEvaluationRunner.QueryResult(
                example,
                result.recipes().stream().map(recipe -> recipe.recipe().recipeId()).toList(),
                result.diagnostics().totalDurationMs() + additionalLatencyMs,
                !result.diagnostics().degradationReasons().isEmpty());
    }

    private List<RecipeEvaluationDataset.Example> selectDevelopmentExamples(
            RecipeEvaluationDataset dataset, int requestedLimit) {
        int limit = Math.max(1, requestedLimit);
        List<RecipeEvaluationDataset.Example> dev = dataset.examples().stream()
                .filter(example -> "dev".equals(example.split())).toList();
        LinkedHashSet<RecipeEvaluationDataset.Example> selected = new LinkedHashSet<>();
        for (String slice : SLICE_ORDER) {
            dev.stream().filter(example -> slice.equals(example.slice())).findFirst().ifPresent(selected::add);
            if (selected.size() >= limit) return selected.stream().limit(limit).toList();
        }
        dev.forEach(selected::add);
        return selected.stream().limit(limit).toList();
    }

    private RecipeQuery query(RecipeEvaluationDataset.Example example) {
        return new RecipeQuery(example.query(), example.requiredIngredients(), example.excludedIngredients(),
                example.requiredTools(), example.maxMinutes());
    }

    private RecipeRagProperties properties() {
        RecipeRagProperties properties = new RecipeRagProperties();
        properties.setSourceCommit(COMMIT);
        properties.setCorpusPath(requiredProperty("diet.recipe-rag.corpus-path"));
        properties.setLuceneDirectory(temporaryIndex.resolve("lucene").toString());
        properties.getEmbedding().setBaseUrl(System.getProperty(
                "diet.recipe-rag.embedding.base-url", "http://localhost:11434"));
        properties.getEmbedding().setModelName(System.getProperty(
                "diet.recipe-rag.embedding.model-name", "bge-m3:567m"));
        properties.getEmbedding().setDimensions(Integer.getInteger("diet.recipe-rag.embedding.dimensions", 1024));
        properties.getQdrant().setBaseUrl(System.getProperty(
                "diet.recipe-rag.qdrant.base-url", "http://localhost:16333"));
        properties.getQdrant().setCollection(System.getProperty(
                "diet.recipe-rag.qdrant.collection", "diet_recipe_chunks_bge_m3_1024_rewrite_eval"));
        return properties;
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + name);
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record CaseComparison(String queryId, String slice, String originalQuery,
                                  String effectiveQuery, boolean changed, String rewriteStatus,
                                  String errorType, long rewriteLatencyMs,
                                  List<String> baselineTop10, List<String> rewriteTop10,
                                  List<String> relevantRecipeIds) { }
}
