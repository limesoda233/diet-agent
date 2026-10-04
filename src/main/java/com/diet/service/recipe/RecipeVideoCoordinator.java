package com.diet.service.recipe;

import com.diet.service.recipe.rag.model.RecipeAnswer;
import com.diet.service.video.CookingVideo;
import com.diet.service.video.CookingVideoSearchService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public final class RecipeVideoCoordinator {
    private final CookingVideoSearchService videos;

    public RecipeVideoCoordinator(CookingVideoSearchService videos) {
        this.videos = videos;
    }

    public Mono<Result> execute(Supplier<RecipeAnswer> recipeCall, String recipeText) {
        Mono<BranchOutcome<RecipeAnswer>> recipe = Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.fromCallable(() -> Objects.requireNonNull(recipeCall.get()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(answer -> new BranchOutcome<>(
                            answer.refused() ? BranchStatus.REFUSED : BranchStatus.SUCCESS,
                            answer, "", elapsedMs(started)))
                    .onErrorResume(error -> Mono.just(new BranchOutcome<>(
                            BranchStatus.FAILED, null, error.getClass().getSimpleName(),
                            elapsedMs(started))));
        });

        Mono<BranchOutcome<List<CookingVideo>>> video = Mono.defer(() -> {
            long started = System.nanoTime();
            return Mono.defer(() -> videos.search(videos.forRecipe(recipeText)))
                    .map(found -> new BranchOutcome<>(
                            found.isEmpty() ? BranchStatus.EMPTY : BranchStatus.SUCCESS,
                            List.copyOf(found), "", elapsedMs(started)))
                    .switchIfEmpty(Mono.fromSupplier(() -> new BranchOutcome<>(
                            BranchStatus.EMPTY, List.of(), "", elapsedMs(started))))
                    .onErrorResume(error -> Mono.just(new BranchOutcome<>(
                            videoStatus(error), List.of(), error.getClass().getSimpleName(),
                            elapsedMs(started))));
        });

        return Mono.zip(recipe, video, Result::new);
    }

    public enum BranchStatus { SUCCESS, REFUSED, EMPTY, DISABLED, TIMEOUT, FAILED }

    public record BranchOutcome<T>(BranchStatus status, T value,
                                   String errorType, long durationMs) { }

    public record Result(BranchOutcome<RecipeAnswer> recipe,
                         BranchOutcome<List<CookingVideo>> video) { }

    private BranchStatus videoStatus(Throwable error) {
        if (error instanceof TimeoutException) return BranchStatus.TIMEOUT;
        if ("video_search_disabled".equals(error.getMessage())) return BranchStatus.DISABLED;
        return BranchStatus.FAILED;
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
