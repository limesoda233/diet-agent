package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RecipeRagasExporterTest {
    @TempDir Path temp;

    @Test
    void writesSnakeCaseUtf8JsonlWithOrderedEvidenceAndMetadata() throws Exception {
        Path output = temp.resolve("nested/ragas-input.jsonl");
        new RecipeRagasExporter(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT))
                .write(output, List.of(sample("rq-001"), sample("rq-002")));
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        var row = new ObjectMapper().readTree(lines.get(0));
        assertThat(row.path("query_id").asText()).isEqualTo("rq-001");
        assertThat(row.path("dataset_version").asText()).isEqualTo("v2");
        assertThat(row.path("dataset_hash").asText()).isEqualTo("hash");
        assertThat(row.path("generator_model").asText()).isEqualTo("model");
        assertThat(row.path("user_input").asText()).isEqualTo("怎么做宫保鸡丁？");
        assertThat(row.path("response").asText()).isEqualTo("炒鸡丁 [S1]\n完成");
        assertThat(row.path("retrieved_contexts").get(0).asText()).isEqualTo("[S1] 菜名：宫保鸡丁");
        assertThat(row.path("retrieved_context_ids").get(1).asText()).isEqualTo("recipe-2");
        assertThat(row.path("reference_context_ids").get(0).asText()).isEqualTo("recipe-1");
        assertThat(row.path("answer_expectation").asText()).isEqualTo("ANSWER");
        assertThat(row.path("citations").size()).isEqualTo(1);
        assertThat(row.path("retrieval_latency_ms").asLong()).isEqualTo(12);
        assertThat(row.path("generation_latency_ms").asLong()).isEqualTo(34);
    }

    @Test
    void projectsAllEvidenceInsteadOfOnlyCitedEvidenceAndClampsLatencies() {
        var dataset = RecipeEvaluationDataset.load("v2");
        var example = dataset.examples().stream().filter(e -> !e.relevantRecipeIds().isEmpty()).findFirst().orElseThrow();
        var answer = new RecipeAnswer("回答 [S1]", List.of(new RecipeCitation("S1", "recipe-1", "标题", "path", "commit", "hash")), false, Map.of(),
                List.of(new RecipeEvidenceContext("S1", "recipe-1", "[S1] 证据一"), new RecipeEvidenceContext("S2", "recipe-2", "[S2] 证据二")));
        var row = RecipeRagasSample.from(dataset, example, answer, "model", -1, -20);
        assertThat(row.retrievedContexts()).containsExactly("[S1] 证据一", "[S2] 证据二");
        assertThat(row.retrievedContextIds()).containsExactly("recipe-1", "recipe-2");
        assertThat(row.referenceContextIds()).containsExactlyElementsOf(example.relevantRecipeIds());
        assertThat(row.datasetHash()).isEqualTo(dataset.fingerprint());
        assertThat(row.citations()).containsExactly("S1");
        assertThat(row.retrievalLatencyMs()).isZero();
        assertThat(row.generationLatencyMs()).isZero();
    }

    @Test
    void validationFailuresPreserveExistingDestination() throws Exception {
        Path output = temp.resolve("ragas.jsonl");
        Files.writeString(output, "old");
        var exporter = new RecipeRagasExporter(new ObjectMapper());
        assertThatThrownBy(() -> exporter.write(output, List.of(sample("same"), sample("same"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query_id");
        assertThatThrownBy(() -> exporter.write(output, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> exporter.write(output, List.of(withEvidence(" ", List.of("context"), List.of("id")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dataset_hash");
        assertThatThrownBy(() -> exporter.write(output, List.of(withEvidence("hash", List.of("context"), List.of()))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retrieved_context");
        assertThat(Files.readString(output)).isEqualTo("old");
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(output); }
    }

    @Test
    void snapshotsListsAndRejectsMalformedFields() {
        var contexts = new ArrayList<>(List.of("context"));
        var row = withEvidence("hash", contexts, List.of("id"));
        contexts.clear();
        assertThat(row.retrievedContexts()).containsExactly("context");
        assertThatThrownBy(() -> row.retrievedContexts().add("other")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sample(" ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("query_id");
        assertThatThrownBy(() -> withEvidence("hash", List.of(" "), List.of("id")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retrieved_contexts");
    }

    @Test
    void serializationFailureRemovesPartialTemporaryFileAndPreservesDestination() throws Exception {
        Path output = temp.resolve("ragas.jsonl");
        Files.writeString(output, "old");
        var module = new SimpleModule();
        module.addSerializer(RecipeRagasSample.class, new JsonSerializer<>() {
            @Override
            public void serialize(RecipeRagasSample value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
                if (value.queryId().equals("rq-002")) throw new IOException("serialization failed");
                generator.writeStartObject();
                generator.writeStringField("query_id", value.queryId());
                generator.writeEndObject();
            }
        });
        var exporter = new RecipeRagasExporter(new ObjectMapper().registerModule(module));
        assertThatThrownBy(() -> exporter.write(output, List.of(sample("rq-001"), sample("rq-002"))))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(output)).isEqualTo("old");
        try (var files = Files.list(temp)) { assertThat(files.toList()).containsExactly(output); }
    }

    private RecipeRagasSample sample(String queryId) {
        return new RecipeRagasSample(queryId, "v2", "hash", "model", "dev", "basic", "怎么做宫保鸡丁？", "炒鸡丁 [S1]\n完成",
                List.of("[S1] 菜名：宫保鸡丁", "[S2] 第二份证据"), List.of("recipe-1", "recipe-2"), List.of("recipe-1"), "ANSWER", false, List.of("S1"), 12, 34);
    }

    private RecipeRagasSample withEvidence(String hash, List<String> contexts, List<String> ids) {
        return new RecipeRagasSample("rq", "v2", hash, "model", "dev", "basic", "问题", "回答", contexts, ids, List.of("gold"), "ANSWER", false, List.of(), 0, 0);
    }
}
