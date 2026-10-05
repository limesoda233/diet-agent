package com.diet.evaluation.recipe;

import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecipeEvaluationDatasetTest {
    @Test
    void legacyV1RemainsLockedAndReproducible() {
        RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load("v1");
        assertThat(dataset.version()).isEqualTo("v1");
        assertThat(dataset.examples()).hasSize(64);
        var sliceCounts = dataset.examples().stream().collect(Collectors.groupingBy(
                RecipeEvaluationDataset.Example::slice, Collectors.counting()));
        assertThat(sliceCounts).hasSize(8).allSatisfy((slice, count) -> assertThat(count).isEqualTo(8));
        assertThat(dataset.devIds()).doesNotContainAnyElementsOf(dataset.testIds());
        assertThat(dataset.testIds()).hasSize(16);
        assertThat(dataset.examples()).allSatisfy(example -> {
            assertThat(example.annotationNote()).isNotBlank();
            if (!example.mustRefuse()) assertThat(example.relevantRecipeIds()).isNotEmpty();
        });
    }

    @Test
    void v2HasExplicitExpectationsBroaderCoverageAndEntityDisjointSplits() {
        RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load("v2");

        assertThat(dataset.version()).isEqualTo("v2");
        assertThat(dataset.examples()).hasSize(90);
        assertThat(dataset.summary()).isEqualTo(new RecipeEvaluationDataset.Summary(
                90, 78, 39, 12, 26, 18, 100.0 / 26));
        assertThat(dataset.examples()).allSatisfy(example -> {
            assertThat(example.annotationNote()).isNotBlank();
            if (example.retrievalExpectation() == RecipeEvaluationDataset.RetrievalExpectation.RELEVANT) {
                assertThat(example.relevantRecipeIds()).isNotEmpty();
                assertThat(example.grades()).containsOnlyKeys(example.relevantRecipeIds().toArray(String[]::new));
                assertThat(example.grades().values()).allMatch(grade -> grade == 2 || grade == 3);
            } else {
                assertThat(example.relevantRecipeIds()).isEmpty();
                assertThat(example.grades()).isEmpty();
            }
        });

        var positive = dataset.examples().stream().filter(RecipeEvaluationDataset.Example::retrievalExpectedRelevant).toList();
        var testPositive = positive.stream().filter(example -> "test".equals(example.split())).toList();
        Set<String> allGold = positive.stream().flatMap(example -> example.relevantRecipeIds().stream())
                .collect(Collectors.toSet());
        Set<String> testGold = testPositive.stream().flatMap(example -> example.relevantRecipeIds().stream())
                .collect(Collectors.toSet());
        Set<String> devGold = positive.stream().filter(example -> "dev".equals(example.split()))
                .flatMap(example -> example.relevantRecipeIds().stream()).collect(Collectors.toSet());

        assertThat(allGold).hasSizeGreaterThanOrEqualTo(30);
        assertThat(positive.stream().filter(example -> example.relevantRecipeIds().size() > 1).count())
                .isGreaterThanOrEqualTo(8);
        assertThat(testPositive).hasSizeGreaterThanOrEqualTo(24);
        assertThat(testGold).hasSizeGreaterThanOrEqualTo(16);
        assertThat(devGold).doesNotContainAnyElementsOf(testGold);
        assertThat(dataset.examples().stream().filter(example -> "near_ood".equals(example.slice())).count())
                .isGreaterThanOrEqualTo(4);
        assertThat(dataset.examples().stream().filter(example ->
                example.answerExpectation() == RecipeEvaluationDataset.AnswerExpectation.REFUSE_UNSUPPORTED).count())
                .isGreaterThanOrEqualTo(4);
        assertThat(new HashSet<>(dataset.devIds())).doesNotContainAnyElementsOf(dataset.testIds());
    }

    @Test
    void v1QrelsFingerprintIsLocked() {
        assertThat(RecipeEvaluationDataset.load("v1").fingerprint())
                .isEqualTo("69ef43df658bd73154eb90daefed967a213aae67bfae20d8cfed0abf5e94bc4f");
    }

    @Test
    void v2QrelsFingerprintIsLocked() {
        assertThat(RecipeEvaluationDataset.load("v2").fingerprint())
                .isEqualTo("f59d67bece51a61cca10f2f70e0424f0ca42f4991003a30d59fbea1fc31de2f6");
    }

    @Test
    void qrelsFingerprintIsIndependentOfPlatformLineEndings() throws Exception {
        byte[] lfQueries = "{\"queryId\":\"q1\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] lfAnswers = "{\"queryId\":\"q1\"}\n".getBytes(StandardCharsets.UTF_8);
        byte[] crlfQueries = "{\"queryId\":\"q1\"}\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] crlfAnswers = "{\"queryId\":\"q1\"}\r\n".getBytes(StandardCharsets.UTF_8);

        assertThat(RecipeEvaluationDataset.sha256Canonical(crlfQueries, crlfAnswers))
                .isEqualTo(RecipeEvaluationDataset.sha256Canonical(lfQueries, lfAnswers));
    }

    @Test
    void v2QrelsIdsResolveToThePinnedCorpusWhenItIsProvided() {
        String root = System.getProperty("diet.recipe-rag.corpus-path");
        assumeTrue(root != null && !root.isBlank(), "Provide the fixed HowToCook checkout to validate qrels IDs");
        var corpus = new RecipeCorpusImporter(new HowToCookMarkdownParser()).importFrom(Path.of(root),
                "a0b14bd388ee996943173679c3a7c9db7da04a6c");
        Map<String, String> idByPath = corpus.documents().stream().collect(Collectors.toMap(
                document -> document.relativePath(), document -> document.recipeId()));
        RecipeEvaluationDataset.load("v2").examples().stream()
                .filter(RecipeEvaluationDataset.Example::retrievalExpectedRelevant)
                .forEach(example -> assertThat(example.sourcePaths()).zipSatisfy(example.relevantRecipeIds(),
                        (path, id) -> assertThat(idByPath.get(path)).as(path).isEqualTo(id)));
    }
}
