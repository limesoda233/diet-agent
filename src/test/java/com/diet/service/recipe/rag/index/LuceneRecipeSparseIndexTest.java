package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import com.diet.service.recipe.rag.model.RecipeHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneRecipeSparseIndexTest {

    @TempDir Path indexRoot;

    @Test
    void retrievesChineseRecipeByDishNameAndIngredients() throws Exception {
        var corpus = fixtureCorpus();
        try (var index = new LuceneRecipeSparseIndex(indexRoot)) {
            index.rebuild(corpus.documents(), corpus.chunks());

            List<RecipeHit> hits = index.search("番茄 鸡蛋 怎么炒", 5);

            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).parentRecipeId()).isEqualTo(corpus.documents().get(0).recipeId());
            assertThat(hits.get(0).channel()).isEqualTo("BM25");
            assertThat(hits).extracting(RecipeHit::rank)
                    .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, hits.size()).boxed().toList());
        }
    }

    @Test
    void failedRebuildKeepsPreviousActiveVersionReadable() throws Exception {
        var corpus = fixtureCorpus();
        try (var index = new LuceneRecipeSparseIndex(indexRoot)) {
            index.rebuild(corpus.documents(), corpus.chunks());
            String activeBefore = Files.readString(indexRoot.resolve("active.json"));

            assertThatThrownBy(() -> index.rebuild(List.of(), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(Files.readString(indexRoot.resolve("active.json"))).isEqualTo(activeBefore);
            assertThat(index.search("西红柿炒鸡蛋", 3)).isNotEmpty();
        }
    }

    private RecipeCorpusImporter.ImportedCorpus fixtureCorpus() throws URISyntaxException {
        Path root = Path.of(getClass().getResource("/recipe/howtocook").toURI());
        return new RecipeCorpusImporter(new HowToCookMarkdownParser())
                .importFrom(root, "a0b14bd388ee996943173679c3a7c9db7da04a6c");
    }
}
