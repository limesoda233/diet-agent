package com.diet.service.video;

import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FakeCookingVideoSearchProvider implements CookingVideoSearchProvider {
    @Override
    public Mono<List<CookingVideo>> search(String query, VideoPlatform platform, int limit) {
        VideoPlatform selected = platform == VideoPlatform.ANY ? VideoPlatform.BILIBILI : platform;
        String slug = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        List<CookingVideo> videos = new ArrayList<>();
        for (int index = 1; index <= limit; index++) {
            videos.add(new CookingVideo(query, query + "教学示例 " + index,
                    "https://example.invalid/cooking-video/" + slug + "/" + index,
                    selected, "FAKE_PROVIDER", null, "FAKE"));
        }
        return Mono.just(List.copyOf(videos));
    }
}
