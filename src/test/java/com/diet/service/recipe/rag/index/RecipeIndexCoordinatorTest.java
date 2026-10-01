package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.corpus.HowToCookMarkdownParser;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;
import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;
import com.diet.service.recipe.rag.model.RecipeQuery;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeIndexCoordinatorTest {

    @Test
    void switchesBothIndexesOnlyAfterBothBuildsSucceed() throws Exception {
        var sparse = new FakeSparseIndex("previous");
        var vector = new FakeVectorIndex("previous");
        var coordinator = coordinator(sparse, vector);

        var result = coordinator.rebuild(fixtureRoot());

        assertThat(result.documentCount()).isEqualTo(1);
        assertThat(result.chunkCount()).isPositive();
        assertThat(sparse.activeVersion()).isEqualTo(result.indexVersion());
        assertThat(vector.activeVersion()).isEqualTo(result.indexVersion());
        assertThat(sparse.events).containsExactly("stage:" + result.indexVersion(), "activate:" + result.indexVersion());
        assertThat(vector.events).containsExactly("stage:" + result.indexVersion(), "activate:" + result.indexVersion());
    }

    @Test
    void vectorFailureKeepsPreviousVersionAndDiscardsStagedSparseIndex() throws Exception {
        var sparse = new FakeSparseIndex("previous");
        var vector = new FakeVectorIndex("previous");
        vector.failNextBuild = true;
        var coordinator = coordinator(sparse, vector);

        assertThatThrownBy(() -> coordinator.rebuild(fixtureRoot()))
                .isInstanceOf(RecipeRagDependencyException.class)
                .hasMessageContaining("recipe_index_build_failed");

        assertThat(sparse.activeVersion()).isEqualTo("previous");
        assertThat(vector.activeVersion()).isEqualTo("previous");
        assertThat(sparse.events).anyMatch(event -> event.startsWith("discard:"));
    }

    @Test
    void sameCorpusAndModelsProduceTheSameIndexVersion() throws Exception {
        var first = coordinator(new FakeSparseIndex(null), new FakeVectorIndex(null)).rebuild(fixtureRoot());
        var second = coordinator(new FakeSparseIndex(null), new FakeVectorIndex(null)).rebuild(fixtureRoot());
        assertThat(second.indexVersion()).isEqualTo(first.indexVersion());
    }

    private RecipeIndexCoordinator coordinator(FakeSparseIndex sparse, FakeVectorIndex vector) {
        RecipeRagProperties properties = new RecipeRagProperties();
        properties.setSourceCommit("a0b14bd388ee996943173679c3a7c9db7da04a6c");
        return new RecipeIndexCoordinator(new RecipeCorpusImporter(new HowToCookMarkdownParser()), sparse,
                new FakeEmbeddingModel(), vector, properties, "howtocook-v1");
    }

    private Path fixtureRoot() throws Exception {
        return Path.of(getClass().getResource("/recipe/howtocook").toURI());
    }

    private static final class FakeEmbeddingModel implements RecipeEmbeddingModel {
        @Override public Mono<double[]> embed(String text) { return Mono.just(new double[]{text.length(), 1, 0}); }
        @Override public String modelName() { return "fake-embedding"; }
        @Override public int dimensions() { return 3; }
    }

    private static final class FakeSparseIndex implements RecipeSparseIndex {
        private String active;
        private final List<String> events = new ArrayList<>();
        private FakeSparseIndex(String active) { this.active = active; }
        @Override public void rebuild(List<RecipeDocument> documents, List<RecipeChunk> chunks) { }
        @Override public List<RecipeHit> search(String query, int topK) { return List.of(); }
        @Override public void stageVersion(String version, List<RecipeDocument> documents, List<RecipeChunk> chunks) {
            events.add("stage:" + version);
        }
        @Override public void activateVersion(String version) { active = version; events.add("activate:" + version); }
        @Override public void discardVersion(String version) { events.add("discard:" + version); }
        @Override public String activeVersion() { return active; }
    }

    private static final class FakeVectorIndex implements RecipeVectorIndex {
        private String active;
        private boolean failNextBuild;
        private final List<String> events = new ArrayList<>();
        private FakeVectorIndex(String active) { this.active = active; }
        @Override public void replaceVersion(String version, List<RecipeChunk> chunks, List<double[]> vectors) { }
        @Override public Mono<List<RecipeHit>> search(RecipeQuery query, double[] vector, int topK) { return Mono.just(List.of()); }
        @Override public void stageVersion(String version, List<RecipeChunk> chunks, List<double[]> vectors) {
            events.add("stage:" + version);
            if (failNextBuild) throw new RecipeRagDependencyException("qdrant_failed");
        }
        @Override public void activateVersion(String version) { active = version; events.add("activate:" + version); }
        @Override public void discardVersion(String version) { events.add("discard:" + version); }
        @Override public String activeVersion() { return active; }
    }
}
