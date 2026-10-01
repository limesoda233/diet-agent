package com.diet.evaluation.recipe;

import org.junit.jupiter.api.Test;

import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RecipeEvaluationDatasetTest {
    @Test
    void containsEightBalancedSlicesAndDisjointDevelopmentAndTestSets() {
        RecipeEvaluationDataset dataset = RecipeEvaluationDataset.load();
        assertThat(dataset.examples()).hasSize(64);
        var sliceCounts = dataset.examples().stream().collect(java.util.stream.Collectors.groupingBy(
                RecipeEvaluationDataset.Example::slice, java.util.stream.Collectors.counting()));
        assertThat(sliceCounts).hasSize(8).allSatisfy((slice, count) -> assertThat(count).isEqualTo(8));
        assertThat(dataset.devIds()).doesNotContainAnyElementsOf(dataset.testIds());
        assertThat(dataset.testIds()).hasSize(16);
        assertThat(dataset.examples()).allSatisfy(example -> {
            assertThat(example.annotationNote()).isNotBlank();
            if (!example.mustRefuse()) assertThat(example.relevantRecipeIds()).isNotEmpty();
        });
    }

    @Test
    void qrelsFingerprintIsLocked() {
        assertThat(RecipeEvaluationDataset.load().fingerprint())
                .isEqualTo("69ef43df658bd73154eb90daefed967a213aae67bfae20d8cfed0abf5e94bc4f");
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
    void qrelsIdsResolveToThePinnedCorpusWhenItIsProvided() {
        String root = System.getProperty("diet.recipe-rag.corpus-path");
        assumeTrue(root != null && !root.isBlank(), "Provide the fixed HowToCook checkout to validate qrels IDs");
        var corpus = new RecipeCorpusImporter(new HowToCookMarkdownParser()).importFrom(Path.of(root),
                "a0b14bd388ee996943173679c3a7c9db7da04a6c");
        Map<String, String> idByPath = corpus.documents().stream().collect(Collectors.toMap(
                document -> document.relativePath(), document -> document.recipeId()));
        RecipeEvaluationDataset.load().examples().stream().filter(example -> !example.mustRefuse())
                .forEach(example -> assertThat(example.sourcePaths()).zipSatisfy(example.relevantRecipeIds(),
                        (path, id) -> assertThat(idByPath.get(path)).as(path).isEqualTo(id)));
    }
}
