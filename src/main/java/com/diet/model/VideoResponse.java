package com.diet.model;

import com.diet.service.video.CookingVideo;

public record VideoResponse(
        String title,
        String url,
        String platform,
        String creator,
        Integer durationSeconds) {

    public static VideoResponse from(CookingVideo video) {
        return new VideoResponse(video.title(), video.url(), video.platform().name(),
                video.creator(), video.durationSeconds());
    }
}
