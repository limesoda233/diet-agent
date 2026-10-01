package com.diet.service.video;

import reactor.core.publisher.Mono;

import java.util.List;

@FunctionalInterface
public interface CookingVideoSearchProvider {
    Mono<List<CookingVideo>> search(String query, VideoPlatform platform, int limit);
}
