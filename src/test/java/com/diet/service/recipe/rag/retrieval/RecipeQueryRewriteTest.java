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

    @Test
    void safeWrapperFallsBackForSynchronousFailureAndEmptyPublisher() {
        RecipeQuery original = new RecipeQuery("番茄炒蛋", List.of("番茄"), List.of(), List.of(), 15);
        RecipeQueryRewriter synchronousFailure = ignored -> {
            throw new IllegalStateException("failed before returning a publisher");
        };
        RecipeQueryRewriter empty = ignored -> reactor.core.publisher.Mono.empty();

        assertThat(RecipeQueryRewriter.withFallback(synchronousFailure, Duration.ofMillis(20))
                .rewrite(original).block()).isSameAs(original);
        assertThat(RecipeQueryRewriter.withFallback(empty, Duration.ofMillis(20))
                .rewrite(original).block()).isSameAs(original);
    }

    @Test
    void safeWrapperAllowsTextRewriteButKeepsEveryHardConstraintExactly() {
        RecipeQuery original = new RecipeQuery(
                "马铃薯丝咋炒", List.of("土豆"), List.of("香菜"), List.of("炒锅"), 15);
        RecipeQuery unsafeRewrite = new RecipeQuery(
                "酸辣土豆丝 做法", List.of("辣椒"), List.of("葱"), List.of("空气炸锅"), 60);

        RecipeQuery actual = RecipeQueryRewriter.withFallback(
                ignored -> reactor.core.publisher.Mono.just(unsafeRewrite), Duration.ofMillis(20))
                .rewrite(original).block();

        assertThat(actual.text()).isEqualTo("酸辣土豆丝 做法");
        assertThat(actual.requiredIngredients()).containsExactly("土豆");
        assertThat(actual.excludedIngredients()).containsExactly("香菜");
        assertThat(actual.requiredTools()).containsExactly("炒锅");
        assertThat(actual.maxMinutes()).isEqualTo(15);
    }
}
