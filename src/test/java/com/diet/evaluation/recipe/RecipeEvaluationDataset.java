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
    private final String version;
    private final List<Example> examples;
    private final String fingerprint;

    private RecipeEvaluationDataset(String version, List<Example> examples, String fingerprint) {
        this.version = version;
        this.examples = List.copyOf(examples);
        this.fingerprint = fingerprint;
    }

    static RecipeEvaluationDataset load() {
        return load(System.getProperty("diet.recipe-evaluation.version", "v2"));
    }

    static RecipeEvaluationDataset load(String version) {
        if (!Set.of("v1", "v2").contains(version)) {
            throw new IllegalArgumentException("Unsupported recipe evaluation dataset version: " + version);
        }
        String base = "/evaluation/recipe/" + version + "/";
        try {
            byte[] queryBytes = resource(base + "queries.jsonl");
            byte[] answerBytes = resource(base + "answers.jsonl");
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
                RetrievalExpectation retrievalExpectation = retrievalExpectation(version, query);
                AnswerExpectation answerExpectation = answerExpectation(version, query);
                examples.add(new Example(id, query.path("query").asText(), query.path("slice").asText(),
                        query.path("split").asText(), retrievalExpectation, answerExpectation,
                        strings(query.path("requiredIngredients")), strings(query.path("excludedIngredients")),
                        strings(query.path("requiredTools")), nullableInt(query.path("maxMinutes")),
                        strings(answer.path("relevantRecipeIds")), grades(answer.path("grades")),
                        strings(answer.path("requiredFacts")), strings(answer.path("forbiddenFacts")),
                        strings(answer.path("sourcePaths")), answer.path("annotationNote").asText()));
            }
            if (!annotations.isEmpty()) throw new IllegalStateException("Orphan qrels: " + annotations.keySet());
            return new RecipeEvaluationDataset(version, examples, sha256Canonical(queryBytes, answerBytes));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load recipe evaluation dataset " + version, error);
        }
    }

    String version() { return version; }
    List<Example> examples() { return examples; }
    String fingerprint() { return fingerprint; }
    Set<String> devIds() { return idsFor("dev"); }
    Set<String> testIds() { return idsFor("test"); }

    Summary summary() {
        List<Example> relevant = examples.stream().filter(Example::retrievalExpectedRelevant).toList();
        List<Example> testRelevant = relevant.stream().filter(example -> "test".equals(example.split())).toList();
        int uniqueGold = (int) relevant.stream().flatMap(example -> example.relevantRecipeIds().stream()).distinct().count();
        int testUniqueGold = (int) testRelevant.stream().flatMap(example -> example.relevantRecipeIds().stream()).distinct().count();
        int multiGold = (int) relevant.stream().filter(example -> example.relevantRecipeIds().size() > 1).count();
        double impact = testRelevant.isEmpty() ? 0 : 100.0 / testRelevant.size();
        return new Summary(examples.size(), relevant.size(), uniqueGold, multiGold,
                testRelevant.size(), testUniqueGold, impact);
    }

    private Set<String> idsFor(String split) {
        Set<String> ids = new LinkedHashSet<>();
        examples.stream().filter(example -> split.equals(example.split())).forEach(example -> ids.add(example.queryId()));
        return Set.copyOf(ids);
    }

    private static RetrievalExpectation retrievalExpectation(String version, JsonNode query) {
        if ("v1".equals(version)) {
            return query.path("mustRefuse").asBoolean(false)
                    ? RetrievalExpectation.EMPTY : RetrievalExpectation.RELEVANT;
        }
        return enumValue(query, "retrievalExpectation", RetrievalExpectation.class);
    }

    private static AnswerExpectation answerExpectation(String version, JsonNode query) {
        if ("v1".equals(version)) {
            return query.path("mustRefuse").asBoolean(false)
                    ? AnswerExpectation.REFUSE_OUT_OF_CORPUS : AnswerExpectation.ANSWER;
        }
        return enumValue(query, "answerExpectation", AnswerExpectation.class);
    }

    private static <T extends Enum<T>> T enumValue(JsonNode query, String field, Class<T> type) {
        String value = query.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Missing " + field + " for " + query.path("queryId").asText());
        return Enum.valueOf(type, value);
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

    static String sha256Canonical(byte[] first, byte[] second) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(normalizeLineEndings(first));
        digest.update((byte) '\n');
        digest.update(normalizeLineEndings(second));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] normalizeLineEndings(byte[] value) {
        return new String(value, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .getBytes(StandardCharsets.UTF_8);
    }

    enum RetrievalExpectation {
        RELEVANT,
        EMPTY
    }

    enum AnswerExpectation {
        ANSWER,
        REFUSE_OUT_OF_CORPUS,
        REFUSE_UNSUPPORTED
    }

    record Summary(int queryCount, int relevantQueryCount, int uniqueGoldCount,
                   int multiGoldQueryCount, int testRelevantQueryCount,
                   int testUniqueGoldCount, double maxSingleTestQueryImpactPercentage) {
    }

    record Example(String queryId, String query, String slice, String split,
                   RetrievalExpectation retrievalExpectation, AnswerExpectation answerExpectation,
                   List<String> requiredIngredients, List<String> excludedIngredients,
                   List<String> requiredTools, Integer maxMinutes,
                   List<String> relevantRecipeIds, Map<String, Integer> grades,
                   List<String> requiredFacts, List<String> forbiddenFacts,
                   List<String> sourcePaths, String annotationNote) {
        boolean retrievalExpectedRelevant() {
            return retrievalExpectation == RetrievalExpectation.RELEVANT;
        }

        boolean retrievalExpectedEmpty() {
            return retrievalExpectation == RetrievalExpectation.EMPTY;
        }

        boolean mustRefuse() {
            return answerExpectation != AnswerExpectation.ANSWER;
        }
    }
}
