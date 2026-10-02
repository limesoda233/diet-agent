package com.diet.service.video;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface BilibiliVideoMcpGateway {
    Mono<String> searchVideos(String keyword, int count);
}
