package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.model.RecipeEvidenceContext;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

record RecipeRagasSample(
        @JsonProperty("query_id") String queryId,
        @JsonProperty("dataset_version") String datasetVersion,
        @JsonProperty("dataset_hash") String datasetHash,
        @JsonProperty("generator_model") String generatorModel,
        String split,
        String slice,
        @JsonProperty("user_input") String userInput,
        String response,
        @JsonProperty("retrieved_contexts") List<String> retrievedContexts,
        @JsonProperty("retrieved_context_ids") List<String> retrievedContextIds,
        @JsonProperty("reference_context_ids") List<String> referenceContextIds,
        @JsonProperty("answer_expectation") String answerExpectation,
        boolean refused,
        List<String> citations,
        @JsonProperty("retrieval_latency_ms") long retrievalLatencyMs,
        @JsonProperty("generation_latency_ms") long generationLatencyMs) {

    RecipeRagasSample {
        requireText(queryId, "query_id");
        requireText(datasetVersion, "dataset_version");
        requireText(datasetHash, "dataset_hash");
        requireText(generatorModel, "generator_model");
        requireText(split, "split");
        requireText(slice, "slice");
        requireText(userInput, "user_input");
        requireText(response, "response");
        requireText(answerExpectation, "answer_expectation");
        try {
            RecipeEvaluationDataset.AnswerExpectation.valueOf(answerExpectation);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid answer_expectation: " + answerExpectation, invalid);
        }
        retrievedContexts = snapshot(retrievedContexts, "retrieved_contexts");
        retrievedContextIds = snapshot(retrievedContextIds, "retrieved_context_ids");
        referenceContextIds = snapshot(referenceContextIds, "reference_context_ids");
        citations = snapshot(citations, "citations");
        if (retrievedContexts.size() != retrievedContextIds.size()) {
            throw new IllegalArgumentException("retrieved_contexts and retrieved_context_ids counts must match");
        }
        retrievalLatencyMs = Math.max(0, retrievalLatencyMs);
        generationLatencyMs = Math.max(0, generationLatencyMs);
    }

    static RecipeRagasSample from(RecipeEvaluationDataset dataset, RecipeEvaluationDataset.Example example,
                                  RecipeAnswer answer, String generatorModel,
                                  long retrievalLatencyMs, long generationLatencyMs) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(example, "example");
        Objects.requireNonNull(answer, "answer");
        return new RecipeRagasSample(example.queryId(), dataset.version(), dataset.fingerprint(),
                generatorModel, example.split(), example.slice(), example.query(), answer.text(),
                answer.evidenceContexts().stream().map(RecipeEvidenceContext::text).toList(),
                answer.evidenceContexts().stream().map(RecipeEvidenceContext::recipeId).toList(),
                example.relevantRecipeIds(), example.answerExpectation().name(), answer.refused(),
                answer.citations().stream().map(RecipeCitation::id).toList(),
                retrievalLatencyMs, generationLatencyMs);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must be nonblank");
    }

    private static List<String> snapshot(List<String> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " must not be null");
        values.forEach(value -> requireText(value, field));
        return List.copyOf(values);
    }
}
