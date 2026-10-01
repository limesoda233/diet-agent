package com.diet.service.video;

public record CookingVideo(
        String query,
        String title,
        String url,
        VideoPlatform platform,
        String creator,
        Integer durationSeconds,
        String source) {
}
