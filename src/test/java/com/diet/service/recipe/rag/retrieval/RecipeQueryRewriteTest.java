package com.diet.service.recipe.rag.retrieval;

import com.diet.service.recipe.rag.model.RecipeQuery;
import com.diet.service.recipe.rag.model.RetrievedRecipe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeQueryRewriteTest {

    @Test
    void identityRewritePreservesExplicitNegativeConstraints() {
        RecipeQuery original = new RecipeQuery(
                "宫保鸡丁怎么做，不要花生", List.of(), List.of("花生"), List.of(), null);

        RecipeQuery rewritten = new IdentityRecipeQueryRewriter().rewrite(original).block();

        assertThat(rewritten).isSameAs(original);
        assertThat(rewritten.excludedIngredients()).containsExactly("花生");
    }

    @Test
    void safeWrappersFallBackToOriginalQueryAndOrder() {
        RecipeQuery original = new RecipeQuery("番茄炒蛋", List.of(), List.of("葱"), List.of(), null);
        RecipeQueryRewriter failingRewriter = ignored -> reactor.core.publisher.Mono.error(new IllegalStateException("offline"));
        RecipeReranker failingReranker = (query, recipes) -> reactor.core.publisher.Mono.error(new IllegalStateException("offline"));
        List<RetrievedRecipe> originalOrder = List.of();

        assertThat(RecipeQueryRewriter.withFallback(failingRewriter, Duration.ofMillis(20))
                .rewrite(original).block()).isSameAs(original);
        assertThat(RecipeReranker.withFallback(failingReranker, Duration.ofMillis(20))
                .rerank(original, originalOrder).block()).isSameAs(originalOrder);
    }
}
