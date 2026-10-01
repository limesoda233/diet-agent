package com.diet.evaluation.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecipeEvaluationDataset {
    private static final String QUERIES = "/evaluation/recipe/queries.jsonl";
    private static final String ANSWERS = "/evaluation/recipe/answers.jsonl";
    private final List<Example> examples;
    private final String fingerprint;

    private RecipeEvaluationDataset(List<Example> examples, String fingerprint) {
        this.examples = List.copyOf(examples);
        this.fingerprint = fingerprint;
    }

    static RecipeEvaluationDataset load() {
        try {
            byte[] queryBytes = resource(QUERIES);
            byte[] answerBytes = resource(ANSWERS);
            ObjectMapper json = new ObjectMapper();
            Map<String, JsonNode> annotations = new HashMap<>();
            for (String line : lines(answerBytes)) {
                JsonNode node = json.readTree(line);
                annotations.put(node.path("queryId").asText(), node);
            }
            List<Example> examples = new ArrayList<>();
            Set<String> ids = new LinkedHashSet<>();
            for (String line : lines(queryBytes)) {
                JsonNode query = json.readTree(line);
                String id = query.path("queryId").asText();
                if (id.isBlank() || !ids.add(id)) throw new IllegalStateException("Duplicate or empty queryId: " + id);
                JsonNode answer = annotations.remove(id);
                if (answer == null) throw new IllegalStateException("Missing qrels for " + id);
                examples.add(new Example(id, query.path("query").asText(), query.path("slice").asText(),
                        query.path("split").asText(), query.path("mustRefuse").asBoolean(false),
                        strings(query.path("requiredIngredients")), strings(query.path("excludedIngredients")),
                        strings(query.path("requiredTools")), nullableInt(query.path("maxMinutes")),
                        strings(answer.path("relevantRecipeIds")), grades(answer.path("grades")),
                        strings(answer.path("requiredFacts")), strings(answer.path("forbiddenFacts")),
                        strings(answer.path("sourcePaths")), answer.path("annotationNote").asText()));
            }
            if (!annotations.isEmpty()) throw new IllegalStateException("Orphan qrels: " + annotations.keySet());
            return new RecipeEvaluationDataset(examples, sha256(queryBytes, answerBytes));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load recipe evaluation dataset", error);
        }
    }

    List<Example> examples() { return examples; }
    String fingerprint() { return fingerprint; }
    Set<String> devIds() { return idsFor("dev"); }
    Set<String> testIds() { return idsFor("test"); }

    private Set<String> idsFor(String split) {
        Set<String> ids = new LinkedHashSet<>();
        examples.stream().filter(example -> split.equals(example.split())).forEach(example -> ids.add(example.queryId()));
        return Set.copyOf(ids);
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream input = RecipeEvaluationDataset.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing resource " + path);
            return input.readAllBytes();
        }
    }

    private static List<String> lines(byte[] value) {
        return new String(value, StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static Map<String, Integer> grades(JsonNode node) {
        if (!node.isObject()) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asInt()));
        return Map.copyOf(result);
    }

    private static Integer nullableInt(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static String sha256(byte[] first, byte[] second) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(first);
        digest.update((byte) '\n');
        digest.update(second);
        return HexFormat.of().formatHex(digest.digest());
    }

    record Example(String queryId, String query, String slice, String split, boolean mustRefuse,
                   List<String> requiredIngredients, List<String> excludedIngredients,
                   List<String> requiredTools, Integer maxMinutes,
                   List<String> relevantRecipeIds, Map<String, Integer> grades,
                   List<String> requiredFacts, List<String> forbiddenFacts,
                   List<String> sourcePaths, String annotationNote) {
    }
}
