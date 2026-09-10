package com.diet.evaluation.retrieval;

import com.diet.enums.SourceMode;
import com.diet.model.SlotBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public record RetrievalDataset(List<Meal> meals, List<Query> queries, List<String> ingredientVocabulary) {
    public static final List<String> SLOT_NAMES = List.of("mealTime","mood","scene","healthGoal","cuisine","taste","convenience");
    public static final String RESOURCE_ROOT = "/evaluation/retrieval/";

    public static RetrievalDataset load() throws IOException {
        try (var corpus = resource("corpus.json"); var queries = resource("queries.json")) {
            return read(corpus, queries);
        }
    }
    static RetrievalDataset read(InputStream corpus, InputStream queries) throws IOException {
        ObjectMapper mapper = JsonMapper.builder()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        var document = mapper.readValue(corpus, Corpus.class);
        var meals = document.meals();
        var cases = mapper.readValue(queries, Queries.class).queries();
        validate(meals, cases, document.ingredientVocabulary());
        return new RetrievalDataset(List.copyOf(meals), List.copyOf(cases), List.copyOf(document.ingredientVocabulary()));
    }
    static InputStream resource(String file) throws IOException {
        var stream = RetrievalDataset.class.getResourceAsStream(RESOURCE_ROOT + file);
        if (stream == null) throw new FileNotFoundException("Missing benchmark resource: " + file);
        return stream;
    }
    public static void validate(List<Meal> meals, List<Query> queries, List<String> ingredientVocabulary) {
        require(meals != null && !meals.isEmpty(), "empty corpus");
        require(queries != null && !queries.isEmpty(), "empty queries");
        require(ingredientVocabulary != null && !ingredientVocabulary.isEmpty()
                && ingredientVocabulary.stream().allMatch(s -> s != null && !s.isBlank() && s.equals(s.strip()))
                && new HashSet<>(ingredientVocabulary).size() == ingredientVocabulary.size(), "invalid ingredient vocabulary");
        Set<String> vocabulary = Set.copyOf(ingredientVocabulary);
        Set<Long> ids = new HashSet<>();
        for (Meal m : meals) {
            require(m.id() > 0 && ids.add(m.id()), "duplicate/invalid meal ID");
            require(m.name() != null && !m.name().isBlank(), "missing meal name");
            require(m.sourceMode() != null && (m.sourceMode() == SourceMode.PUBLIC ? m.ownerUserId() == null : m.ownerUserId() != null), "invalid meal scope");
            validateSlots(m.slots());
            LocalDateTime.parse(m.updatedAt());
            require(m.ingredients() != null && !m.ingredients().isEmpty() && vocabulary.containsAll(m.ingredients()), "meal ingredient vocabulary mismatch");
            require(m.provenance() != null && "synthetic_recipe_assumption".equals(m.provenance().get("recipeStatus")), "missing recipe provenance");
            require(m.description() != null && !m.description().isBlank() && m.preparation() != null && !m.preparation().isBlank(), "missing recipe description");
        }
        Set<String> queryIds = new HashSet<>();
        Map<String,String> groupSplits = new HashMap<>();
        for (Query q : queries) {
            require(q.id() != null && !q.id().isBlank() && queryIds.add(q.id()), "duplicate/invalid query ID");
            require(q.groupId() != null && !q.groupId().isBlank(), "missing group");
            require(Set.of("dev","test").contains(q.split()), "invalid split");
            String prior = groupSplits.putIfAbsent(q.groupId(), q.split());
            require(prior == null || prior.equals(q.split()), "group crosses dev/test: " + q.groupId());
            require(q.sourceMode() != null && (q.sourceMode() != SourceMode.PERSONAL || q.userId() != null), "invalid query scope");
            validateSlots(q.slots());
            require(q.utterance() != null && !q.utterance().isBlank() && q.rationale() != null && !q.rationale().isBlank(), "missing query/rationale");
            require(q.slice() != null && !q.slice().isBlank() && q.context() != null, "missing slice/context");
            require(Set.of("draft","reviewed").contains(q.annotationStatus()), "invalid annotation status");
            require(q.excludeMealIds() != null && ids.containsAll(q.excludeMealIds()) && new HashSet<>(q.excludeMealIds()).size() == q.excludeMealIds().size(), "invalid exclude IDs");
            require(q.requiredIngredients() != null && q.excludedIngredients() != null && Collections.disjoint(q.requiredIngredients(),q.excludedIngredients()), "invalid ingredient constraints");
            require(vocabulary.containsAll(q.requiredIngredients()) && vocabulary.containsAll(q.excludedIngredients()), "query ingredient vocabulary mismatch: " + q.id());
            Set<Long> allowed = meals.stream().filter(q::allows).map(Meal::id).collect(Collectors.toSet());
            require(q.relevanceGrades() != null && q.relevanceGrades().keySet().equals(allowed), "judgment coverage: " + q.id());
            require(q.relevanceGrades().values().stream().allMatch(g -> g != null && g >= 0 && g <= 2), "invalid grade");
            for (Meal m : meals) if (q.relevanceGrades().getOrDefault(m.id(),0) > 0) {
                require(!q.excludeMealIds().contains(m.id()) && Collections.disjoint(m.ingredients(),q.excludedIngredients()) && m.ingredients().containsAll(q.requiredIngredients()), "positive judgment violates constraint: " + q.id() + "/" + m.id());
            }
        }
    }
    private static void validateSlots(Map<String,List<String>> slots) {
        require(slots != null && slots.keySet().equals(Set.copyOf(SLOT_NAMES)) && slots.values().stream().allMatch(v -> v != null && v.stream().allMatch(s -> s != null && !s.isBlank())), "invalid slots");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    static SlotBundle slots(Map<String,List<String>> values) {
        return new SlotBundle(values.get("mealTime"),values.get("mood"),values.get("scene"),values.get("healthGoal"),values.get("cuisine"),values.get("taste"),values.get("convenience"));
    }
    public record Corpus(String version, String annotationStatus, List<Meal> meals, List<String> ingredientVocabulary) {}
    public record Queries(String version, String annotationStatus, List<Query> queries) {}
    public record Meal(long id, SourceMode sourceMode, Long ownerUserId, String name,
                       Map<String,List<String>> slots, String updatedAt, List<String> ingredients,
                       String description, String preparation, Map<String,String> provenance) {}
    public record Query(String id, String groupId, String split, String slice, String utterance,
                        List<String> context, SourceMode sourceMode, Long userId, Map<String,List<String>> slots,
                        List<Long> excludeMealIds, List<String> excludedIngredients, List<String> requiredIngredients,
                        Map<Long,Integer> relevanceGrades, String rationale, String annotationStatus) {
        public boolean allows(Meal m) {
            return sourceMode == m.sourceMode() && (sourceMode == SourceMode.PUBLIC ? m.ownerUserId() == null : Objects.equals(userId,m.ownerUserId()));
        }
    }
}
