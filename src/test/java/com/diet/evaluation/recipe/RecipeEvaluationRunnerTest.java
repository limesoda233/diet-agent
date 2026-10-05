package com.diet.evaluation.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeEvaluationRunnerTest {

    @Test
    void evaluatesUnsupportedAnswersAsRetrievalPositiveAndReportsNearOodFalsePositives() {
        var examples = RecipeEvaluationDataset.load("v2").examples().stream()
                .filter(example -> List.of("rq-083", "rq-087").contains(example.queryId()))
                .toList();

        var result = new RecipeEvaluationRunner().run(RecipeExperiment.BM25, examples,
                (experiment, example) -> new RecipeEvaluationRunner.QueryResult(
                        example,
                        example.retrievalExpectedRelevant()
                                ? List.of(example.relevantRecipeIds().get(0))
                                : List.of("unexpected-recipe"),
                        5,
                        false));

        assertThat(result.relevantQueryCount()).isEqualTo(1);
        assertThat(result.emptyExpectedQueryCount()).isEqualTo(1);
        assertThat(result.recallAt1()).isEqualTo(0.25);
        assertThat(result.emptyExpectationAccuracy()).isEqualTo(0.0);
        assertThat(result.nearOodFalsePositiveRate()).isEqualTo(1.0);
    }
}
