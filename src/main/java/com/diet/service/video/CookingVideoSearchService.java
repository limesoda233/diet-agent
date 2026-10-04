package com.diet.service.video;

import com.diet.service.recipe.RecipeQueryRouter;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class CookingVideoSearchService {
    public static final String TOOL_NAME = "search_cooking_videos";
    private static final Pattern VIDEO_REQUEST = Pattern.compile("(视频|跟做|教程|链接|哔哩哔哩|B站|bilibili|抖音)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECIPE_QUERY_CLEANUP = Pattern.compile(
            "(怎么做|咋做|如何做|怎么弄|咋弄|做法|菜谱|食谱|烹饪步骤|制作步骤|需要什么食材|要哪些食材|有视频吗|视频教程)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_ONLY = Pattern.compile(
            "(只要|只看|仅看).{0,8}(视频|教程)|(不用|不要).{0,8}(文字|菜谱|做法)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED_FIELDS = Set.of("query", "platform", "limit");
    private final CookingVideoSearchProvider provider;
    private final VideoMcpProperties properties;
    private final RecipeQueryRouter recipeRouter = new RecipeQueryRouter();

    public CookingVideoSearchService(CookingVideoSearchProvider provider, VideoMcpProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    public boolean matches(String message, Map<String, Object> context) {
        return (context != null && context.containsKey("videoSearch"))
                || (message != null && VIDEO_REQUEST.matcher(message).find());
    }

    public VideoSearchRequest route(String message, Map<String, Object> context) {
        Map<String, Object> safe = context == null ? Map.of() : context;
        Object structured = safe.get("videoSearch");
        if (structured != null) return structured(structured);
        if (!matches(message, safe)) throw new IllegalArgumentException("不是视频搜索请求");
        VideoPlatform platform = inferPlatform(message);
        String query = message.replaceAll("(?i)哔哩哔哩|bilibili|B站|抖音|教学视频|视频教程|教程视频|视频|教程|链接|跟做|帮我|请|找", "")
                .replaceAll("^[的：:，,。\\s]+|[的：:，,。\\s]+$", "").trim();
        if (query.isBlank()) throw new IllegalArgumentException("视频搜索 query 不能为空");
        return new VideoSearchRequest(query, platform, validatedLimit(properties.getDefaultLimit()));
    }

    public Mono<List<CookingVideo>> search(String query, VideoPlatform platform, int limit) {
        if (!properties.isEnabled()) return Mono.error(new IllegalStateException("video_search_disabled"));
        if (query == null || query.isBlank()) return Mono.error(new IllegalArgumentException("query 不能为空"));
        VideoPlatform safePlatform = platform == null ? VideoPlatform.ANY : platform;
        int safeLimit = validatedLimit(limit);
        Duration timeout = properties.getTimeout() == null ? Duration.ofSeconds(3) : properties.getTimeout();
        return provider.search(query.trim(), safePlatform, safeLimit).timeout(timeout);
    }

    public Mono<List<CookingVideo>> search(VideoSearchRequest request) {
        return search(request.query(), request.platform(), request.limit());
    }

    public String format(List<CookingVideo> videos) {
        if (videos == null || videos.isEmpty()) return "没有找到可用的做饭教学视频。";
        return "找到 " + videos.size() + " 个做饭教学视频，点击卡片即可观看。";
    }

    public boolean isVideoOnly(String message, Map<String, Object> context) {
        Map<String, Object> safe = context == null ? Map.of() : context;
        if (message != null && VIDEO_ONLY.matcher(message).find()) return true;
        if (recipeRouter.matches(message, safe)) return false;
        return safe.containsKey("videoSearch")
                || (message != null && VIDEO_REQUEST.matcher(message).find());
    }

    public VideoSearchRequest forRecipe(String recipeText) {
        if (recipeText == null || recipeText.isBlank()) {
            throw new IllegalArgumentException("菜谱视频 query 不能为空");
        }
        String query = RECIPE_QUERY_CLEANUP.matcher(recipeText).replaceAll(" ")
                .replaceAll("(?i)哔哩哔哩|bilibili|B站|抖音|视频|教程|链接|帮我|请|啊|呀", " ")
                .replaceAll("[？?！!，,。\\s]+", " ")
                .trim();
        if (query.isBlank()) query = recipeText.trim();
        return new VideoSearchRequest(query, VideoPlatform.ANY,
                validatedLimit(properties.getDefaultLimit()));
    }

    private VideoSearchRequest structured(Object structured) {
        if (!(structured instanceof Map<?, ?> raw)) throw new IllegalArgumentException("videoSearch 必须是对象");
        Set<String> unknown = new LinkedHashSet<>();
        raw.keySet().forEach(key -> {
            if (!(key instanceof String name) || !ALLOWED_FIELDS.contains(name)) unknown.add(String.valueOf(key));
        });
        if (!unknown.isEmpty()) throw new IllegalArgumentException("videoSearch 包含未知字段: " + String.join(", ", unknown));
        Object queryValue = raw.get("query");
        if (!(queryValue instanceof String query) || query.isBlank()) throw new IllegalArgumentException("videoSearch.query 不能为空");
        VideoPlatform platform = parsePlatform(raw.get("platform"));
        int limit = raw.get("limit") == null ? properties.getDefaultLimit() : integer(raw.get("limit"), "limit");
        return new VideoSearchRequest(query.trim(), platform, validatedLimit(limit));
    }

    private VideoPlatform inferPlatform(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("哔哩哔哩") || lower.contains("b站") || lower.contains("bilibili")) return VideoPlatform.BILIBILI;
        if (lower.contains("抖音")) return VideoPlatform.DOUYIN;
        return VideoPlatform.ANY;
    }

    private VideoPlatform parsePlatform(Object value) {
        if (value == null) return VideoPlatform.ANY;
        if (!(value instanceof String text)) throw new IllegalArgumentException("platform 必须是字符串");
        try {
            return VideoPlatform.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("platform 只允许 BILIBILI、DOUYIN、ANY");
        }
    }

    private int integer(Object value, String field) {
        if (value instanceof Number number) return number.intValue();
        throw new IllegalArgumentException(field + " 必须是整数");
    }

    private int validatedLimit(int limit) {
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("limit 必须在 1 到 10 之间");
        return limit;
    }

    public record VideoSearchRequest(String query, VideoPlatform platform, int limit) {
    }
}
