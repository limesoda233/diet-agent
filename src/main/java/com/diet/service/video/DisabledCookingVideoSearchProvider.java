package com.diet.service.video;

import reactor.core.publisher.Mono;

import java.util.List;

public class DisabledCookingVideoSearchProvider implements CookingVideoSearchProvider {
    @Override
    public Mono<List<CookingVideo>> search(String query, VideoPlatform platform, int limit) {
        return Mono.error(new IllegalStateException("video_search_disabled"));
    }
}
