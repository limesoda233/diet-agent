package com.diet.service.recipe;

import com.diet.agent.factory.AgentFactory;
import com.diet.mapper.MealMapper;
import com.diet.service.meal.knowledge.MealKnowledgeRepository;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.index.RecipeEmbeddingModel;
import com.diet.service.recipe.rag.index.RecipeSparseIndex;
import com.diet.service.recipe.rag.index.RecipeVectorIndex;
import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.recipe.rag.model.RecipeCitation;
import com.diet.service.recipe.rag.retrieval.RecipeQueryRewriter;
import com.diet.service.recipe.rag.retrieval.RecipeReranker;
import com.diet.service.trace.AgentTraceService;
import com.diet.service.video.CookingVideo;
import com.diet.service.video.CookingVideoSearchProvider;
import com.diet.service.video.CookingVideoSearchService;
import com.diet.service.video.DisabledCookingVideoSearchProvider;
import com.diet.service.video.VideoMcpProperties;
import com.diet.service.video.VideoPlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.diet.service.recipe.RecipeVideoCoordinator.BranchStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RecipeVideoCoordinatorTest {
    private static final String QUESTION = "宫保鸡丁咋做啊";
    private static final CookingVideo VIDEO = new CookingVideo("宫保鸡丁", "宫保鸡丁教程",
            "https://example.invalid/BV1", VideoPlatform.BILIBILI, "测试UP", 120, "fake");

    @Test
    void startsRecipeAndVideoBranchesBeforeEitherCompletes() {
        var bothStarted = new CountDownLatch(2);
        var answer = groundedAnswer();
        CookingVideoSearchProvider provider = (query, platform, limit) -> Mono.fromCallable(() -> {
            bothStarted.countDown();
            assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of(VIDEO);
        }).subscribeOn(Schedulers.boundedElastic());

        var result = coordinator(provider, Duration.ofSeconds(4)).execute(() -> {
            bothStarted.countDown();
            try {
                assertThat(bothStarted.await(2, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return answer;
        }, QUESTION).block(Duration.ofSeconds(5));

        assertRecipeSuccess(result, answer);
        assertVideoSuccess(result);
    }

    @Test
    void runsBlockingRecipeCallOnBoundedElasticFromNonblockingSubscriber() {
        var thread = new AtomicReference<String>();
        var answer = groundedAnswer();
        var result = successfulVideos().execute(() -> {
            thread.set(Thread.currentThread().getName());
            assertThat(Schedulers.isInNonBlockingThread()).isFalse();
            return answer;
        }, QUESTION).subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(3));

        assertRecipeSuccess(result, answer);
        assertVideoSuccess(result);
        assertThat(thread.get()).startsWith("boundedElastic-");
    }

    @Test
    void retainsRefusalAndSuccessfulVideos() {
        var answer = RecipeAnswer.refused("证据不足", Map.of());
        var result = successfulVideos().execute(() -> answer, QUESTION).block();

        assertThat(result.recipe().status()).isEqualTo(BranchStatus.REFUSED);
        assertThat(result.recipe().value()).isSameAs(answer);
        assertThat(result.recipe().errorType()).isEmpty();
        assertVideoSuccess(result);
        assertDurations(result);
    }

    @Test
    void retainsRecipeWhenVideoListIsEmpty() {
        var answer = groundedAnswer();
        var result = coordinator((query, platform, limit) -> Mono.just(List.of()))
                .execute(() -> answer, QUESTION).block();

        assertThat(result.video().status()).isEqualTo(BranchStatus.EMPTY);
        assertThat(result.video().value()).isEmpty();
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsRecipeWhenVideoPublisherCompletesEmpty() {
        var answer = groundedAnswer();
        var result = coordinator((query, platform, limit) -> Mono.empty())
                .execute(() -> answer, QUESTION).block();

        assertThat(result.video().status()).isEqualTo(BranchStatus.EMPTY);
        assertThat(result.video().value()).isEmpty();
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsRecipeWhenVideoTimesOut() {
        var answer = groundedAnswer();
        var result = coordinator((query, platform, limit) -> Mono.never(), Duration.ofMillis(50))
                .execute(() -> answer, QUESTION).block(Duration.ofSeconds(3));

        assertThat(result.video().status()).isEqualTo(BranchStatus.TIMEOUT);
        assertThat(result.video().errorType()).isEqualTo("TimeoutException");
        assertThat(result.video().value()).isEmpty();
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsRecipeWhenVideoIsDisabled() {
        var answer = groundedAnswer();
        var videos = new CookingVideoSearchService(new DisabledCookingVideoSearchProvider(),
                new VideoMcpProperties());
        var result = new RecipeVideoCoordinator(videos).execute(() -> answer, QUESTION).block();

        assertThat(result.video().status()).isEqualTo(BranchStatus.DISABLED);
        assertThat(result.video().errorType()).isEqualTo("IllegalStateException");
        assertThat(result.video().value()).isEmpty();
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsVideoWhenRecipeFailsWithoutExposingExceptionMessage() {
        var result = successfulVideos().execute(() -> {
            throw new IllegalStateException("secret external payload");
        }, QUESTION).block();

        assertThat(result.recipe().status()).isEqualTo(BranchStatus.FAILED);
        assertThat(result.recipe().value()).isNull();
        assertThat(result.recipe().errorType()).isEqualTo("IllegalStateException");
        assertThat(result.toString()).doesNotContain("secret external payload");
        assertVideoSuccess(result);
        assertDurations(result);
    }

    @Test
    void retainsRecipeWhenVideoFailsWithoutExposingExceptionMessage() {
        var answer = groundedAnswer();
        var result = coordinator((query, platform, limit) ->
                Mono.error(new IllegalArgumentException("secret external payload")))
                .execute(() -> answer, QUESTION).block();

        assertThat(result.video().status()).isEqualTo(BranchStatus.FAILED);
        assertThat(result.video().errorType()).isEqualTo("IllegalArgumentException");
        assertThat(result.video().value()).isEmpty();
        assertThat(result.toString()).doesNotContain("secret external payload");
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsRecipeWhenVideoProviderThrowsBeforeReturningPublisher() {
        var answer = groundedAnswer();
        var result = coordinator((query, platform, limit) -> {
            throw new IllegalStateException("secret external payload");
        }).execute(() -> answer, QUESTION).block();

        assertThat(result.video().status()).isEqualTo(BranchStatus.FAILED);
        assertThat(result.video().errorType()).isEqualTo("IllegalStateException");
        assertThat(result.toString()).doesNotContain("secret external payload");
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsRecipeWhenVideoRequestCannotBeBuilt() {
        var answer = groundedAnswer();
        var result = successfulVideos().execute(() -> answer, null).block();

        assertThat(result.video().status()).isEqualTo(BranchStatus.FAILED);
        assertThat(result.video().errorType()).isEqualTo("IllegalArgumentException");
        assertRecipeSuccess(result, answer);
    }

    @Test
    void retainsVideoWhenRecipeReturnsNull() {
        var result = successfulVideos().execute(() -> null, QUESTION).block();

        assertThat(result.recipe().status()).isEqualTo(BranchStatus.FAILED);
        assertThat(result.recipe().value()).isNull();
        assertVideoSuccess(result);
        assertDurations(result);
    }

    @Test
    void snapshotsVideoListIntoImmutableOutcome() {
        var answer = groundedAnswer();
        var found = new ArrayList<>(List.of(VIDEO));
        var result = coordinator((query, platform, limit) -> Mono.just(found))
                .execute(() -> answer, QUESTION).block();
        found.clear();

        assertVideoSuccess(result);
        assertThatThrownBy(() -> result.video().value().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertRecipeSuccess(result, answer);
    }

    @Test
    void defersBothCallsAndRepeatsThemForEachSubscription() {
        var recipeCalls = new AtomicInteger();
        var videoCalls = new AtomicInteger();
        var answer = groundedAnswer();
        var execution = coordinator((query, platform, limit) -> {
            videoCalls.incrementAndGet();
            assertThat(query).isEqualTo("宫保鸡丁");
            return Mono.just(List.of(VIDEO));
        }).execute(() -> {
            recipeCalls.incrementAndGet();
            return answer;
        }, QUESTION);

        assertThat(recipeCalls).hasValue(0);
        assertThat(videoCalls).hasValue(0);
        for (int subscription = 1; subscription <= 2; subscription++) {
            var result = execution.block();
            assertRecipeSuccess(result, answer);
            assertVideoSuccess(result);
            assertThat(recipeCalls).hasValue(subscription);
            assertThat(videoCalls).hasValue(subscription);
        }
    }

    @Test
    void registersCoordinatorOnlyWhenRecipeRagIsEnabledWithVideoDisabledByDefault() {
        var properties = new RecipeRagProperties();
        properties.setCorpusPath("target/nonexistent-coordinator-test-corpus");
        var contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(RecipeQueryConfiguration.class)
                .withBean(CookingVideoSearchService.class, () -> new CookingVideoSearchService(
                        new DisabledCookingVideoSearchProvider(), new VideoMcpProperties()))
                .withBean(RecipeRagProperties.class, () -> properties)
                .withBean(AgentFactory.class, () -> mock(AgentFactory.class))
                .withBean(AgentTraceService.class, () -> mock(AgentTraceService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RecipeSparseIndex.class, () -> mock(RecipeSparseIndex.class))
                .withBean(RecipeEmbeddingModel.class, () -> mock(RecipeEmbeddingModel.class))
                .withBean(RecipeVectorIndex.class, () -> mock(RecipeVectorIndex.class))
                .withBean(MealKnowledgeRepository.class, () -> mock(MealKnowledgeRepository.class))
                .withBean(MealMapper.class, () -> mock(MealMapper.class))
                .withBean(RecipeQueryRewriter.class, () -> mock(RecipeQueryRewriter.class))
                .withBean(RecipeReranker.class, () -> mock(RecipeReranker.class));

        contextRunner.withPropertyValues("diet.recipe-rag.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RecipeVideoCoordinator.class);
        });
        contextRunner.withPropertyValues("diet.recipe-rag.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RecipeVideoCoordinator.class);
            var answer = groundedAnswer();
            var result = context.getBean(RecipeVideoCoordinator.class)
                    .execute(() -> answer, QUESTION).block();
            assertThat(result.video().status()).isEqualTo(BranchStatus.DISABLED);
            assertRecipeSuccess(result, answer);
        });
    }

    private RecipeVideoCoordinator successfulVideos() {
        return coordinator((query, platform, limit) -> Mono.just(List.of(VIDEO)));
    }

    private RecipeVideoCoordinator coordinator(CookingVideoSearchProvider provider) {
        return coordinator(provider, Duration.ofSeconds(1));
    }

    private RecipeVideoCoordinator coordinator(CookingVideoSearchProvider provider, Duration timeout) {
        var properties = new VideoMcpProperties();
        properties.setEnabled(true);
        properties.setTimeout(timeout);
        return new RecipeVideoCoordinator(new CookingVideoSearchService(provider, properties));
    }

    private RecipeAnswer groundedAnswer() {
        return new RecipeAnswer("宫保鸡丁做法 [S1]", List.of(
                new RecipeCitation("S1", "r1", "宫保鸡丁", "path.md", "commit", "hash")),
                false, Map.of());
    }

    private void assertRecipeSuccess(RecipeVideoCoordinator.Result result, RecipeAnswer answer) {
        assertThat(result).isNotNull();
        assertThat(result.recipe().status()).isEqualTo(BranchStatus.SUCCESS);
        assertThat(result.recipe().value()).isSameAs(answer);
        assertThat(result.recipe().errorType()).isEmpty();
        assertDurations(result);
    }

    private void assertVideoSuccess(RecipeVideoCoordinator.Result result) {
        assertThat(result).isNotNull();
        assertThat(result.video().status()).isEqualTo(BranchStatus.SUCCESS);
        assertThat(result.video().value()).containsExactly(VIDEO);
        assertThat(result.video().errorType()).isEmpty();
        assertDurations(result);
    }

    private void assertDurations(RecipeVideoCoordinator.Result result) {
        assertThat(result.recipe().durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.video().durationMs()).isGreaterThanOrEqualTo(0);
    }
}
