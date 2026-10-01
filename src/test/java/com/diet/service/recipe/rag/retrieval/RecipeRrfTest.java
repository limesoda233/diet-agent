package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeRrfTest {

    @Test
    void fusesChannelsAtParentRecipeLevelWithStableTieBreaking() {
        List<RecipeHit> bm25 = List.of(
                hit("a-steps", "recipe-a", 1, "BM25"),
                hit("b-steps", "recipe-b", 2, "BM25"),
                hit("a-extra", "recipe-a", 3, "BM25"));
        List<RecipeHit> dense = List.of(
                hit("b-extra", "recipe-b", 1, "DENSE"),
                hit("a-ingredients", "recipe-a", 2, "DENSE"));

        List<RecipeHit> fused = RecipeRrf.fuse(bm25, dense, 60);

        assertThat(fused).extracting(RecipeHit::parentRecipeId)
                .containsExactly("recipe-a", "recipe-b");
        assertThat(fused).extracting(RecipeHit::rank).containsExactly(1, 2);
        assertThat(fused).allSatisfy(hit -> assertThat(hit.channel()).isEqualTo("RRF"));
    }

    private RecipeHit hit(String chunkId, String parentId, int rank, String channel) {
        return new RecipeHit(chunkId, parentId, parentId, RecipeChunk.ChunkType.STEPS,
                "evidence", 1.0 / rank, rank, channel);
    }
}
