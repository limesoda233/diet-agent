package com.diet.service.recipe.rag.corpus;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class RecipeCorpusImporter {

    static final int MAX_CHUNK_CHARS = 1200;
    static final int CHUNK_OVERLAP_CHARS = 120;

    private final HowToCookMarkdownParser parser;

    public RecipeCorpusImporter(HowToCookMarkdownParser parser) {
        this.parser = parser;
    }

    public ImportedCorpus importFrom(Path root, String sourceCommit) {
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("HowToCook root must be an existing directory");
        }
        if (sourceCommit == null || sourceCommit.isBlank()) {
            throw new IllegalArgumentException("sourceCommit must not be blank");
        }

        List<RecipeDocument> documents = new ArrayList<>();
        List<RecipeChunk> chunks = new ArrayList<>();
        Path dishesRoot = root.resolve("dishes");
        if (!Files.isDirectory(dishesRoot)) {
            throw new IllegalArgumentException("HowToCook root must contain dishes/");
        }

        try (var paths = Files.walk(dishesRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> isRecipeMarkdown(dishesRoot.relativize(path)))
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .forEach(path -> importFile(root, path, sourceCommit, documents, chunks));
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to scan HowToCook corpus", error);
        }

        List<RecipeCorpusManifest.Entry> entries = documents.stream()
                .map(document -> new RecipeCorpusManifest.Entry(
                        document.relativePath(), document.recipeId(), document.contentHash(),
                        (int) chunks.stream().filter(chunk -> chunk.parentRecipeId().equals(document.recipeId())).count()))
                .toList();
        String manifestMaterial = sourceCommit + "\n" + entries.stream()
                .map(entry -> entry.relativePath() + "\n" + entry.recipeId() + "\n" + entry.contentHash()
                        + "\n" + entry.chunkCount())
                .reduce("", (left, right) -> left + right + "\n");
        RecipeCorpusManifest manifest = new RecipeCorpusManifest(
                "HowToCook", sourceCommit, documents.size(), chunks.size(), sha256(manifestMaterial), entries);
        return new ImportedCorpus(documents, chunks, manifest);
    }

    private void importFile(Path root, Path path, String sourceCommit,
                            List<RecipeDocument> documents, List<RecipeChunk> chunks) {
        try {
            String markdown = Files.readString(path, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
            var parsed = parser.parse(markdown);
            if (parsed.isEmpty()) {
                return;
            }
            String relativePath = normalize(root.relativize(path));
            String contentHash = sha256(markdown);
            String recipeId = sha256(sourceCommit + "\n" + relativePath + "\n" + contentHash);
            HowToCookMarkdownParser.ParsedRecipe recipe = parsed.orElseThrow();
            RecipeDocument document = new RecipeDocument(
                    recipeId, recipe.title(), recipe.aliases(), recipe.ingredients(), recipe.steps(),
                    recipe.introduction(), recipe.sections(), relativePath, sourceCommit, contentHash);
            documents.add(document);
            chunks.addAll(createChunks(document));
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to import recipe " + path, error);
        }
    }

    private List<RecipeChunk> createChunks(RecipeDocument document) {
        Map<RecipeChunk.ChunkType, String> sectionText = new LinkedHashMap<>();
        sectionText.put(RecipeChunk.ChunkType.INTRODUCTION, document.introduction());
        sectionText.put(RecipeChunk.ChunkType.INGREDIENTS,
                findSection(document.sections(), "必备原料和工具", "必备原料", "原料"));
        sectionText.put(RecipeChunk.ChunkType.CALCULATION, findSection(document.sections(), "计算"));
        sectionText.put(RecipeChunk.ChunkType.STEPS, findSection(document.sections(), "操作", "步骤"));
        sectionText.put(RecipeChunk.ChunkType.ADDITIONAL, findSection(document.sections(), "附加内容", "附加"));

        List<RecipeChunk> chunks = new ArrayList<>();
        for (Map.Entry<RecipeChunk.ChunkType, String> entry : sectionText.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String prefix = "菜名：" + document.title() + "\n章节：" + heading(entry.getKey()) + "\n";
            int order = 0;
            for (String text : splitWithOverlap(prefix, entry.getValue())) {
                String chunkId = sha256(document.recipeId() + "\n" + entry.getKey() + "\n" + order);
                chunks.add(new RecipeChunk(chunkId, document.recipeId(), entry.getKey(), order,
                        text, sha256(text)));
                order++;
            }
        }
        return List.copyOf(chunks);
    }

    private List<String> splitWithOverlap(String prefix, String body) {
        int bodyLimit = MAX_CHUNK_CHARS - prefix.length();
        if (bodyLimit <= CHUNK_OVERLAP_CHARS) {
            throw new IllegalStateException("Recipe chunk prefix is too long");
        }
        String normalized = body.trim();
        if (normalized.length() <= bodyLimit) {
            return List.of(prefix + normalized);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(start + bodyLimit, normalized.length());
            int end = findBoundary(normalized, start, hardEnd);
            if (end <= start) {
                end = hardEnd;
            }
            parts.add(prefix + normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(end - CHUNK_OVERLAP_CHARS, start + 1);
        }
        return List.copyOf(parts);
    }

    private int findBoundary(String text, int start, int hardEnd) {
        int minimum = Math.min(hardEnd, start + Math.max(1, (hardEnd - start) / 2));
        for (int index = hardEnd; index > minimum; index--) {
            char value = text.charAt(index - 1);
            if (value == '\n' || value == '。' || value == '；' || value == '！' || value == '？') {
                return index;
            }
        }
        return hardEnd;
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

    private String heading(RecipeChunk.ChunkType type) {
        return switch (type) {
            case INTRODUCTION -> "简介";
            case INGREDIENTS -> "必备原料和工具";
            case CALCULATION -> "计算";
            case STEPS -> "操作";
            case ADDITIONAL -> "附加内容";
        };
    }

    private boolean isRecipeMarkdown(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return false;
        }
        if (fileName.equalsIgnoreCase("README.md") || fileName.equalsIgnoreCase("index.md")) {
            return false;
        }
        for (Path part : path) {
            if (part.toString().startsWith(".")) {
                return false;
            }
        }
        return true;
    }

    private String normalize(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record ImportedCorpus(
            List<RecipeDocument> documents,
            List<RecipeChunk> chunks,
            RecipeCorpusManifest manifest) {

        public ImportedCorpus {
            documents = List.copyOf(documents);
            chunks = List.copyOf(chunks);
        }
    }
}
