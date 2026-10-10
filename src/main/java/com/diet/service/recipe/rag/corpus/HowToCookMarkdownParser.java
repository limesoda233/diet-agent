package com.diet.service.recipe.rag.corpus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 针对固定 HowToCook Markdown 约定的解析器，并非通用文档解析平台。
 * 一级标题给出菜名，二级章节提供食材与步骤；步骤的嵌套列表合并回上一条，保留操作解释。
 * 缺少标题、食材或步骤时返回 empty，由导入器跳过，不能把目录页当作有效菜谱。
 * 清理链接和行内标记只是便于检索，不推断原文未提供的营养、工具或时长事实。
 */
public class HowToCookMarkdownParser {

    private static final Pattern HEADING = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[*+-]\\s+(.+?)\\s*$");
    private static final Pattern ALIAS = Pattern.compile("(?:也叫|又叫|亦称)[`“\"]?([^，。；、`”\"\\s]{2,12})");

    public Optional<ParsedRecipe> parse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Optional.empty();
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        String title = parseTitle(lines);
        if (title.isBlank()) {
            return Optional.empty();
        }

        StringBuilder introduction = new StringBuilder();
        Map<String, StringBuilder> sectionBuilders = new LinkedHashMap<>();
        String currentSection = null;
        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                currentSection = heading.group(1).trim();
                sectionBuilders.putIfAbsent(currentSection, new StringBuilder());
                continue;
            }
            if (line.startsWith("# ")) {
                continue;
            }
            if (currentSection == null) {
                appendLine(introduction, line);
            } else {
                appendLine(sectionBuilders.get(currentSection), line);
            }
        }

        Map<String, String> sections = new LinkedHashMap<>();
        sectionBuilders.forEach((key, value) -> sections.put(key, value.toString().trim()));
        String ingredientSection = findSection(sections, "必备原料和工具", "必备原料", "原料");
        String operationSection = findSection(sections, "操作", "步骤");
        List<String> ingredients = parseBullets(ingredientSection, false);
        List<String> steps = parseBullets(operationSection, true);
        if (ingredients.isEmpty() || steps.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ParsedRecipe(
                title,
                parseAliases(introduction.toString()),
                ingredients,
                steps,
                introduction.toString().trim(),
                sections));
    }

    private String parseTitle(String[] lines) {
        for (String line : lines) {
            if (line.startsWith("# ")) {
                String title = cleanInline(line.substring(2));
                return title.endsWith("的做法")
                        ? title.substring(0, title.length() - "的做法".length()).trim()
                        : title;
            }
        }
        return "";
    }

    private List<String> parseAliases(String text) {
        Set<String> aliases = new LinkedHashSet<>();
        Matcher matcher = ALIAS.matcher(text);
        while (matcher.find()) {
            aliases.add(matcher.group(1));
        }
        return List.copyOf(aliases);
    }

    private List<String> parseBullets(String section, boolean mergeNested) {
        if (section == null || section.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String line : section.split("\n")) {
            Matcher bullet = BULLET.matcher(line);
            if (!bullet.matches()) {
                continue;
            }
            String value = cleanInline(bullet.group(2));
            boolean nested = !bullet.group(1).isEmpty();
            if (mergeNested && nested && !values.isEmpty()) {
                int last = values.size() - 1;
                values.set(last, values.get(last) + "；" + value);
            } else if (!nested || !mergeNested) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String findSection(Map<String, String> sections, String... names) {
        for (String name : names) {
            for (Map.Entry<String, String> entry : sections.entrySet()) {
                if (entry.getKey().contains(name)) {
                    return entry.getValue();
                }
            }
        }
        return "";
    }

    private String cleanInline(String value) {
        return value.replace("`", "")
                .replace("**", "")
                .replaceAll("\\[(.+?)]\\(.+?\\)", "$1")
                .trim();
    }

    private void appendLine(StringBuilder target, String line) {
        if (!target.isEmpty()) {
            target.append('\n');
        }
        target.append(line);
    }

    public record ParsedRecipe(
            String title,
            List<String> aliases,
            List<String> ingredients,
            List<String> steps,
            String introduction,
            Map<String, String> sections) {

        public ParsedRecipe {
            aliases = List.copyOf(aliases);
            ingredients = List.copyOf(ingredients);
            steps = List.copyOf(steps);
            sections = Map.copyOf(sections);
        }
    }
}
