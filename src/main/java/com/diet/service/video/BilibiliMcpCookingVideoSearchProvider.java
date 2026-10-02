package com.diet.service.video;

import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BilibiliMcpCookingVideoSearchProvider implements CookingVideoSearchProvider {
    private static final String SOURCE = "mcp:bilibili:search_videos@0.0.7";
    private static final Pattern HEADER = Pattern.compile("(?m)^\\d+\\.\\s+\"(.*?)\"\\s+-\\s+([^\\r\\n]+)$");
    private static final Pattern BVID = Pattern.compile("(?m)^\\s*BV ID:\\s*(BV[0-9A-Za-z]+)\\s*$");
    private static final Pattern DURATION = Pattern.compile("(?m)^\\s*Duration:\\s*([^\\r\\n]+)\\s*$");
    private final BilibiliVideoMcpGateway gateway;
    private final String querySuffix;

    public BilibiliMcpCookingVideoSearchProvider(BilibiliVideoMcpGateway gateway, String querySuffix) {
        this.gateway = gateway;
        this.querySuffix = querySuffix == null ? "" : querySuffix;
    }

    @Override
    public Mono<List<CookingVideo>> search(String query, VideoPlatform platform, int limit) {
        if (platform == VideoPlatform.DOUYIN) {
            return Mono.error(new IllegalArgumentException("douyin_video_search_not_configured"));
        }
        String keyword = query.trim() + querySuffix;
        return gateway.searchVideos(keyword.trim(), limit).map(result -> parse(query.trim(), result, limit));
    }

    private List<CookingVideo> parse(String originalQuery, String text, int limit) {
        if (text.contains("No videos found related to")) return List.of();
        List<CookingVideo> videos = new ArrayList<>();
        for (String block : text.split("\\R\\s*\\R")) {
            Matcher header = HEADER.matcher(block);
            Matcher bvid = BVID.matcher(block);
            if (!header.find() || !bvid.find()) continue;
            Matcher duration = DURATION.matcher(block);
            Integer durationSeconds = duration.find() ? durationSeconds(duration.group(1).trim()) : null;
            videos.add(new CookingVideo(originalQuery, plainText(header.group(1)),
                    "https://www.bilibili.com/video/" + bvid.group(1), VideoPlatform.BILIBILI,
                    plainText(header.group(2)), durationSeconds, SOURCE));
            if (videos.size() == limit) break;
        }
        if (videos.isEmpty()) throw new IllegalStateException("bilibili_mcp_unrecognized_response");
        return List.copyOf(videos);
    }

    private String plainText(String value) {
        return HtmlUtils.htmlUnescape(value.replaceAll("<[^>]+>", ""))
                .replaceAll("\\s+", " ").trim();
    }

    private Integer durationSeconds(String value) {
        String[] parts = value.split(":");
        try {
            if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60
                        + Integer.parseInt(parts[2]);
            }
            return null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
