package com.diet.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RetrievalDatasetTest {
    @Test
    void jsonIntegersCannotBeSilentlyCoercedOrDuplicated() throws Exception {
        var mapper=new ObjectMapper();
        byte[] corpus;
        byte[] queries;
        try(var c=RetrievalDataset.resource("corpus.json");var q=RetrievalDataset.resource("queries.json")) {
            corpus=c.readAllBytes(); queries=q.readAllBytes();
        }
        var fractionalGrade=mapper.readTree(queries);
        ((com.fasterxml.jackson.databind.node.ObjectNode)fractionalGrade.path("queries").get(0).path("relevanceGrades")).put("1",2.9);
        assertThatThrownBy(()->RetrievalDataset.read(new java.io.ByteArrayInputStream(corpus),new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(fractionalGrade)))).isInstanceOf(java.io.IOException.class);
        var stringGrade=mapper.readTree(queries);
        ((com.fasterxml.jackson.databind.node.ObjectNode)stringGrade.path("queries").get(0).path("relevanceGrades")).put("1","2");
        assertThatThrownBy(()->RetrievalDataset.read(new java.io.ByteArrayInputStream(corpus),new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(stringGrade)))).isInstanceOf(java.io.IOException.class);
        var fractionalId=mapper.readTree(corpus);
        ((com.fasterxml.jackson.databind.node.ObjectNode)fractionalId.path("meals").get(0)).put("id",1.9);
        assertThatThrownBy(()->RetrievalDataset.read(new java.io.ByteArrayInputStream(mapper.writeValueAsBytes(fractionalId)),new java.io.ByteArrayInputStream(queries))).isInstanceOf(java.io.IOException.class);
        String duplicate=mapper.writeValueAsString(mapper.readTree(corpus)).replaceFirst("\"id\":1,","\"id\":1,\"id\":1,");
        assertThatThrownBy(()->RetrievalDataset.read(new java.io.ByteArrayInputStream(duplicate.getBytes(java.nio.charset.StandardCharsets.UTF_8)),new java.io.ByteArrayInputStream(queries))).isInstanceOf(java.io.IOException.class);
    }
    @Test
    void misspelledIngredientCannotSilentlyBecomeANoMatchCase() throws Exception {
        var data=RetrievalDataset.load();
        var mapper=new ObjectMapper();
        var tree=mapper.readTree(getClass().getResourceAsStream("/evaluation/retrieval/queries.json"));
        for(var node:tree.path("queries")) if(node.path("id").asText().equals("empty-lobster-1")) {
            ((com.fasterxml.jackson.databind.node.ArrayNode)node.path("requiredIngredients")).set(0,mapper.getNodeFactory().textNode("龙吓"));
        }
        var invalid=mapper.treeToValue(tree,RetrievalDataset.Queries.class);
        assertThatThrownBy(()->RetrievalDataset.validate(data.meals(),invalid.queries(),data.ingredientVocabulary())).hasMessageContaining("ingredient vocabulary");
    }
    @Test
    void recipeEnrichmentPreservesTheNamedStaple() throws Exception {
        var data=RetrievalDataset.load();
        assertThat(data.meals().stream().filter(m->m.name().equals("黑椒牛肉意面")).findFirst().orElseThrow().ingredients())
                .contains("意大利面").doesNotContain("米饭");
        assertThat(data.meals().stream().filter(m->m.name().equals("日式鸡肉荞麦面")).findFirst().orElseThrow().ingredients())
                .contains("荞麦面").doesNotContain("米饭");
    }
    @Test
    void validatesJudgmentCoverageAndSplitIsolation() throws Exception {
        var data = RetrievalDataset.load();
        assertThat(data.meals()).hasSize(45);
        assertThat(data.queries()).hasSize(64);
        assertThat(data.queries().stream().map(RetrievalDataset.Query::slice).distinct()).containsExactlyInAnyOrder(
                "direct_tag","paraphrase","multi_preference","excluded_ingredient","required_ingredient","adjust","isolation","no_match");
        var root = new ObjectMapper().readTree(getClass().getResourceAsStream("/evaluation/retrieval/queries.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("queries").get(0).path("relevanceGrades")).remove("1");
        var bad = new ObjectMapper().treeToValue(root, RetrievalDataset.Queries.class);
        assertThatThrownBy(() -> RetrievalDataset.validate(data.meals(), bad.queries(),data.ingredientVocabulary())).hasMessageContaining("judgment coverage");
        var clean = new ObjectMapper().readTree(getClass().getResourceAsStream("/evaluation/retrieval/queries.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) clean.path("queries").get(1)).put("split", "test");
        var crossed = new ObjectMapper().treeToValue(clean, RetrievalDataset.Queries.class);
        assertThatThrownBy(() -> RetrievalDataset.validate(data.meals(), crossed.queries(),data.ingredientVocabulary())).hasMessageContaining("group crosses");
    }
    @Test
    void frozenCorpusAndQueriesExistBeforeAnyRetrieverRuns() throws Exception {
        var mapper = new ObjectMapper();
        var corpus = getClass().getResourceAsStream("/evaluation/retrieval/corpus.json");
        var queries = getClass().getResourceAsStream("/evaluation/retrieval/queries.json");
        assertThat(corpus).as("versioned corpus is required").isNotNull();
        assertThat(queries).as("independently authored judgments are required").isNotNull();
        try (corpus; queries) {
            assertThat(mapper.readTree(corpus).path("meals").size()).isEqualTo(45);
            assertThat(mapper.readTree(queries).path("queries").size()).isGreaterThanOrEqualTo(64);
        }
    }
}
