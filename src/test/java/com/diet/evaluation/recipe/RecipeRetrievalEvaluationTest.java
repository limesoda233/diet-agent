package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import com.diet.service.recipe.rag.index.AgentScopeRecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.LuceneRecipeSparseIndex;
import com.diet.service.recipe.rag.index.QdrantRecipeVectorIndex;
import com.diet.service.recipe.rag.index.RecipeIndexCoordinator;
import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.retrieval.HybridRecipeRetriever;
import com.diet.service.recipe.rag.retrieval.RecipeParentResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import io.agentscope.core.model.ExecutionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecipeRetrievalEvaluationTest {
    private static final String COMMIT = "a0b14bd388ee996943173679c3a7c9db7da04a6c";
    @TempDir Path temporaryIndex;

    @Test
    void writesSevenExperimentReportWhenLiveDependenciesAreEnabled() {
        assumeTrue(Boolean.getBoolean("diet.recipe-rag.live"),
                "Set -Ddiet.recipe-rag.live=true with HowToCook, Ollama and Qdrant available");
        Path corpusRoot = Path.of(requiredProperty("diet.recipe-rag.corpus-path"));
        RecipeRagProperties properties = properties();
        var importer = new RecipeCorpusImporter(new HowToCookMarkdownParser());
        var corpus = importer.importFrom(corpusRoot, COMMIT);
        var sparse = new LuceneRecipeSparseIndex(temporaryIndex.resolve("lucene"));
        var agentScopeEmbedding = OllamaTextEmbedding.builder()
                .baseUrl(properties.getEmbedding().getBaseUrl())
                .modelName(properties.getEmbedding().getModelName())
                .dimensions(properties.getEmbedding().getDimensions())
                .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(10)).maxAttempts(1).build())
                .build();
        var embedding = new AgentScopeRecipeEmbeddingModel(agentScopeEmbedding);
        var vector = new QdrantRecipeVectorIndex(properties,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
        var coordinator = new RecipeIndexCoordinator(importer, sparse, embedding, vector,
                properties, "howtocook-v1");
        var build = coordinator.rebuild(corpusRoot);
        var retriever = new HybridRecipeRetriever(sparse, embedding, vector,
                new RecipeParentResolver(corpus.documents()), Duration.ofSeconds(10), 20, 20, 10, 60);
        RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load();
        List<RecipeEvaluationDataset.Example> lockedTest = dataset.examples().stream()
                .filter(example -> "test".equals(example.split())).toList();
        RecipeEvaluationRunner runner = new RecipeEvaluationRunner();
        List<RecipeEvaluationReport.ExperimentResult> experiments = new ArrayList<>();
        for (RecipeExperiment experiment : RecipeExperiment.values()) {
            experiments.add(runner.run(experiment, lockedTest, (configured, example) -> {
                RecipeQuery query = new RecipeQuery(example.query(), example.requiredIngredients(),
                        example.excludedIngredients(), example.requiredTools(), example.maxMinutes());
                var result = retriever.retrieve(query, configured.mode()).block();
                if (result == null) throw new IllegalStateException("recipe retrieval returned no signal");
                return new RecipeEvaluationRunner.QueryResult(example,
                        result.recipes().stream().map(recipe -> recipe.recipe().recipeId()).toList(),
                        result.diagnostics().totalDurationMs(), !result.diagnostics().degradationReasons().isEmpty());
            }));
        }
        Map<String, Object> answerMetrics = new LinkedHashMap<>();
        answerMetrics.put("status", "not_run");
        answerMetrics.put("reason", "This command evaluates retrieval. Run answer evaluation with a configured main chat model.");
        answerMetrics.put("optionalStages", "rerank and rewrite use identity baselines until model providers are configured");
        var report = new RecipeEvaluationReport(new RecipeEvaluationReport.Metadata(COMMIT,
                corpus.manifest().corpusHash(), dataset.fingerprint(), "1.0-SNAPSHOT",
                embedding.modelName(), embedding.dimensions()), experiments, answerMetrics);
        report.write(Path.of("target/evaluation/recipe/report.json"));
    }

    private RecipeRagProperties properties() {
        RecipeRagProperties properties = new RecipeRagProperties();
        properties.setSourceCommit(COMMIT);
        properties.setCorpusPath(requiredProperty("diet.recipe-rag.corpus-path"));
        properties.setLuceneDirectory(temporaryIndex.resolve("lucene").toString());
        properties.getEmbedding().setBaseUrl(System.getProperty("diet.recipe-rag.embedding.base-url", "http://localhost:11434"));
        properties.getEmbedding().setModelName(System.getProperty("diet.recipe-rag.embedding.model-name", "bge-m3:567m"));
        properties.getEmbedding().setDimensions(Integer.getInteger("diet.recipe-rag.embedding.dimensions", 1024));
        properties.getQdrant().setBaseUrl(System.getProperty("diet.recipe-rag.qdrant.base-url", "http://localhost:6333"));
        properties.getQdrant().setCollection(System.getProperty("diet.recipe-rag.qdrant.collection",
                "diet_recipe_chunks_bge_m3_1024_eval"));
        return properties;
    }

    private String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing -D" + name);
        return value;
    }
}
