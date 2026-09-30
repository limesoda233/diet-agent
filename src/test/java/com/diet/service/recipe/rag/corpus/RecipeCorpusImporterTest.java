package com.diet.service.recipe.rag.corpus;

import com.diet.service.recipe.rag.model.RecipeChunk;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeCorpusImporterTest {

    private static final String COMMIT = "a0b14bd388ee996943173679c3a7c9db7da04a6c";
    private final RecipeCorpusImporter importer = new RecipeCorpusImporter(new HowToCookMarkdownParser());

    @Test
    void importsDeterministicParentDocumentsChunksAndManifest() throws URISyntaxException {
        Path root = Path.of(getClass().getResource("/recipe/howtocook").toURI());

        RecipeCorpusImporter.ImportedCorpus first = importer.importFrom(root, COMMIT);
        RecipeCorpusImporter.ImportedCorpus second = importer.importFrom(root, COMMIT);

        assertThat(second).isEqualTo(first);
        assertThat(first.documents()).singleElement().satisfies(recipe -> {
            assertThat(recipe.title()).isEqualTo("西红柿炒鸡蛋");
            assertThat(recipe.ingredients()).contains("西红柿", "鸡蛋");
            assertThat(recipe.steps()).hasSize(3);
            assertThat(recipe.relativePath()).isEqualTo("dishes/vegetable_dish/fixture.md");
            assertThat(recipe.recipeId()).hasSize(64);
            assertThat(recipe.contentHash()).hasSize(64);
        });
        assertThat(first.chunks()).extracting(RecipeChunk::type)
                .contains(RecipeChunk.ChunkType.INGREDIENTS,
                        RecipeChunk.ChunkType.CALCULATION,
                        RecipeChunk.ChunkType.STEPS,
                        RecipeChunk.ChunkType.ADDITIONAL);
        assertThat(first.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.chunkId()).hasSize(64);
            assertThat(chunk.text().length()).isLessThanOrEqualTo(1200);
        });
        assertThat(first.manifest().sourceCommit()).isEqualTo(COMMIT);
        assertThat(first.manifest().documentCount()).isEqualTo(1);
        assertThat(first.manifest().chunkCount()).isEqualTo(first.chunks().size());
        assertThat(first.manifest().corpusHash()).hasSize(64);
    }

    @Test
    void sourceCommitParticipatesInStableIds() throws URISyntaxException {
        Path root = Path.of(getClass().getResource("/recipe/howtocook").toURI());

        var pinned = importer.importFrom(root, COMMIT);
        var anotherCommit = importer.importFrom(root, "another-commit");

        assertThat(anotherCommit.documents().get(0).recipeId())
                .isNotEqualTo(pinned.documents().get(0).recipeId());
        assertThat(anotherCommit.documents().get(0).contentHash())
                .isEqualTo(pinned.documents().get(0).contentHash());
    }
}
