package com.diet.service.recipe;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多轮菜品指代解析器，在 query rewrite 之前回答“用户问的是哪道菜”。
 * 序号读取最新展示顺序；省略菜名时优先当前选中菜，其次唯一候选；多候选或越界先追问。
 * 这是确定性规则，不理解任意复杂指代，也不把完整历史拼进模型。
 * Resolution 中 query 供两条分支使用，clarification 非空时禁止启动检索。
 */
public final class RecipeConversationResolver {
    private static final Pattern ORDINAL = Pattern.compile("^第([一二三四五六七八九十0-9]+)(?:道|个)(?:菜)?");
    private static final Pattern SELECTION_NUMBER = Pattern.compile("^([一二三四五六七八九十0-9]+)[。.!！?？\\s]*$");
    private static final Pattern BARE = Pattern.compile(
            "^(?:请问|请)?(?:这道菜|这个菜|这道|这个|它)?(?:的)?(?:怎么做|咋做|如何做|怎么弄|咋弄|怎么炒|怎么蒸|怎么炖|需要什么食材|要哪些食材|做法|菜谱|食谱)(?:[？?！!。\\s]*|[，,].*)$");

    public boolean isSelectionReply(String text, RecipeConversationContext context) {
        return context.pendingSelection() && (ordinalReference(text.trim(), context) != null
                || context.displayedMeals().stream().anyMatch(dish -> dish.name().equals(text.trim()))
                || context.displayedMeals().isEmpty() && isDishNameReply(text));
    }

    public Resolution resolve(String message, RecipeConversationContext context) {
        String text = message.trim();
        // 显式序号优先于 activeDish，不能让“第二道”继续查询旧选中的第一道。
        var ordinal = ordinalReference(text, context);
        if (ordinal != null) {
            int index = ordinal(ordinal.group(1));
            if (index < 1 || index > context.displayedMeals().size()) return ask(context);
            String name = context.displayedMeals().get(index - 1).name();
            String rest = text.substring(ordinal.end()).replaceFirst("^的", "");
            return selected(name + (rest.isBlank() ? "怎么做" : rest), name, "display_ordinal");
        }
        if (context.pendingSelection()) {
            var match = context.displayedMeals().stream().filter(dish -> dish.name().equals(text)).findFirst();
            if (match.isPresent()) return selected(text + "怎么做", text, "clarification_selection");
            if (context.displayedMeals().isEmpty() && isDishNameReply(text)) {
                return selected(text + "怎么做", text, "clarification_dish_name");
            }
        }
        var bare = BARE.matcher(text);
        if (bare.matches()) {
            String name = context.activeDish();
            if (name.isBlank() && context.displayedMeals().size() == 1) name = context.displayedMeals().get(0).name();
            if (name.isBlank()) return ask(context);
            String action = text.replaceFirst("^(?:请问|请)?(?:这道菜|这个菜|这道|这个|它)?(?:的)?", "");
            return selected(name + action, name, "session_dish");
        }
        // An explicit new question takes precedence over the previously selected dish.
        String name = context.displayedMeals().stream().filter(dish -> text.contains(dish.name()))
                .map(RecipeConversationContext.Dish::name).findFirst().orElse("");
        if (name.isBlank()) {
            var action = Pattern.compile("怎么做|咋做|如何做|怎么弄|咋弄|怎么炒|怎么蒸|怎么炖|需要什么食材|要哪些食材|的?做法|的?菜谱|的?食谱").matcher(text);
            if (action.find() && action.start() > 0) name = text.substring(0, action.start())
                    .replaceFirst("^(?:请问|请|我想学做|我想做|教我做)", "").trim();
        }
        return selected(text, name, "explicit_question");
    }

    private Matcher ordinalReference(String text, RecipeConversationContext context) {
        var ordinal = ORDINAL.matcher(text);
        if (ordinal.find()) return ordinal;
        // 单独的“3/三”只有在等待选择且展示过候选时才有确定含义。
        // 路由与解析共用此判断，避免模型兜底把序号原文传给 RAG/MCP；普通数字仍走原意图链路。
        var number = SELECTION_NUMBER.matcher(text);
        return context.pendingSelection() && !context.displayedMeals().isEmpty() && number.matches()
                ? number : null;
    }

    private boolean isDishNameReply(String text) {
        // 只在等待菜名且没有展示候选时接受受限短文本。再次说“做法”仍需澄清，
        // “推荐/取消/你好”等新请求交回正常路由，避免全部当成菜名。
        return !BARE.matcher(text.trim()).matches()
                && text.trim().matches("[\\p{IsHan}A-Za-z·]{2,20}")
                && !Pattern.compile("推荐|吃什么|换一|怎么|如何|什么|不知|算了|不用|取消|不查|你好|谢谢|再见|视频|菜谱|食谱")
                    .matcher(text).find();
    }

    private Resolution ask(RecipeConversationContext context) {
        String choices = java.util.stream.IntStream.range(0, context.displayedMeals().size())
                .mapToObj(i -> (i + 1) + ". " + context.displayedMeals().get(i).name())
                .collect(java.util.stream.Collectors.joining("；"));
        return new Resolution("", "", "ambiguous", choices.isBlank()
                ? "你想看哪道菜的做法？请告诉我菜名。"
                : "你想看哪道菜的做法？" + choices + "。可以回复数字、菜名或第几道。");
    }
    private Resolution selected(String query, String name, String source) {
        return new Resolution(query, name, source, "");
    }
    private int ordinal(String value) {
        if (value.matches("[0-9]+")) {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return -1; }
        }
        return switch (value) {
            case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5;
            case "六" -> 6; case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10;
            default -> -1;
        };
    }
    public record Resolution(String query, String selectedDish, String source, String clarification) { }
}
