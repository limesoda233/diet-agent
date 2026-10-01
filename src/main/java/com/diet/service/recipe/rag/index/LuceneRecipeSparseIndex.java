package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.model.RecipeChunk;
import com.diet.service.recipe.rag.model.RecipeDocument;
import com.diet.service.recipe.rag.model.RecipeHit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LuceneRecipeSparseIndex implements RecipeSparseIndex {

    private static final Pattern ACTIVE_VERSION = Pattern.compile("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String[] SEARCH_FIELDS = {"title", "aliases", "ingredients", "section", "body"};

    private final Path indexRoot;

    public LuceneRecipeSparseIndex(Path indexRoot) {
        this.indexRoot = indexRoot;
    }

    @Override
    public void rebuild(List<RecipeDocument> documents, List<RecipeChunk> chunks) {
        String version = "v-" + UUID.randomUUID();
        stageVersion(version, documents, chunks);
        activateVersion(version);
    }

    @Override
    public void stageVersion(String version, List<RecipeDocument> documents, List<RecipeChunk> chunks) {
        validateVersion(version);
        if (documents == null || documents.isEmpty() || chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("Recipe documents and chunks must not be empty");
        }
        Map<String, RecipeDocument> parents = new HashMap<>();
        documents.forEach(document -> parents.put(document.recipeId(), document));
        if (chunks.stream().anyMatch(chunk -> !parents.containsKey(chunk.parentRecipeId()))) {
            throw new IllegalArgumentException("Every recipe chunk must reference an imported parent document");
        }

        Path building = indexRoot.resolve("." + version + ".building");
        Path completed = indexRoot.resolve(version);
        try {
            Files.createDirectories(indexRoot);
            if (version.equals(activeVersion()) && Files.isDirectory(completed)) return;
            deleteRecursively(building);
            deleteRecursively(completed);
            Files.createDirectory(building);
            try (var directory = FSDirectory.open(building);
                 var analyzer = new StandardAnalyzer();
                 var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                         .setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
                chunks.stream().sorted(Comparator.comparing(RecipeChunk::chunkId))
                        .map(chunk -> toLuceneDocument(chunk, parents.get(chunk.parentRecipeId())))
                        .forEach(document -> add(writer, document));
                writer.commit();
            }
            try (var directory = FSDirectory.open(building);
                 var reader = DirectoryReader.open(directory)) {
                if (reader.numDocs() != chunks.size()) {
                    throw new IllegalStateException("Lucene recipe index validation failed");
                }
            }
            moveAtomically(building, completed);
        } catch (IOException error) {
            deleteRecursively(building);
            throw new UncheckedIOException("Failed to build Lucene recipe index", error);
        } catch (RuntimeException error) {
            deleteRecursively(building);
            throw error;
        }
    }

    @Override
    public void activateVersion(String version) {
        validateVersion(version);
        if (!Files.isDirectory(indexRoot.resolve(version))) {
            throw new IllegalStateException("Recipe sparse index version is unavailable: " + version);
        }
        try {
            switchActiveVersion(version);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to activate Lucene recipe index", error);
        }
    }

    @Override
    public void discardVersion(String version) {
        validateVersion(version);
        if (!version.equals(activeVersion())) deleteRecursively(indexRoot.resolve(version));
        deleteRecursively(indexRoot.resolve("." + version + ".building"));
    }

    @Override
    public String activeVersion() {
        Path pointer = indexRoot.resolve("active.json");
        if (!Files.isRegularFile(pointer)) return null;
        try {
            Matcher matcher = ACTIVE_VERSION.matcher(Files.readString(pointer, StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read recipe sparse index pointer", error);
        }
    }

    @Override
    public List<RecipeHit> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        Path active = activeDirectory();
        try (var directory = FSDirectory.open(active);
             var reader = DirectoryReader.open(directory);
             var analyzer = new StandardAnalyzer()) {
            var parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer);
            var luceneQuery = parser.parse(QueryParser.escape(query));
            var searcher = new IndexSearcher(reader);
            var scoreDocs = searcher.search(luceneQuery, Math.max(topK, topK * 4)).scoreDocs;
            List<RecipeHit> hits = new ArrayList<>();
            for (var scoreDoc : scoreDocs) {
                Document document = searcher.storedFields().document(scoreDoc.doc);
                hits.add(new RecipeHit(
                        document.get("chunkId"),
                        document.get("parentRecipeId"),
                        document.get("title"),
                        RecipeChunk.ChunkType.valueOf(document.get("chunkType")),
                        document.get("body"),
                        scoreDoc.score,
                        0,
                        "BM25"));
            }
            hits.sort(Comparator.comparingDouble(RecipeHit::score).reversed()
                    .thenComparing(RecipeHit::parentRecipeId)
                    .thenComparing(RecipeHit::chunkId));
            List<RecipeHit> ranked = new ArrayList<>();
            for (int index = 0; index < Math.min(topK, hits.size()); index++) {
                ranked.add(hits.get(index).withRank(index + 1));
            }
            return List.copyOf(ranked);
        } catch (org.apache.lucene.queryparser.classic.ParseException error) {
            throw new IllegalArgumentException("Invalid recipe search query", error);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to search Lucene recipe index", error);
        }
    }

    private Document toLuceneDocument(RecipeChunk chunk, RecipeDocument parent) {
        Document document = new Document();
        document.add(new StringField("chunkId", chunk.chunkId(), Field.Store.YES));
        document.add(new StringField("parentRecipeId", parent.recipeId(), Field.Store.YES));
        document.add(new StringField("chunkType", chunk.type().name(), Field.Store.YES));
        document.add(new TextField("title", parent.title(), Field.Store.YES));
        document.add(new TextField("aliases", String.join(" ", parent.aliases()), Field.Store.NO));
        document.add(new TextField("ingredients", String.join(" ", parent.ingredients()), Field.Store.NO));
        document.add(new TextField("section", chunk.type().name(), Field.Store.NO));
        document.add(new TextField("body", chunk.text(), Field.Store.YES));
        document.add(new StoredField("contentHash", chunk.contentHash()));
        return document;
    }

    private void add(IndexWriter writer, Document document) {
        try {
            writer.addDocument(document);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private Path activeDirectory() {
        String version = activeVersion();
        if (version == null) {
            throw new IllegalStateException("Recipe sparse index has not been built");
        }
        Path active = indexRoot.resolve(version).normalize();
        if (!active.startsWith(indexRoot.normalize()) || !Files.isDirectory(active)) {
            throw new IllegalStateException("Recipe sparse index version is unavailable");
        }
        return active;
    }

    private void switchActiveVersion(String version) throws IOException {
        Path temporary = indexRoot.resolve("active.json.tmp");
        Files.writeString(temporary, "{\"version\":\"" + version + "\"}\n", StandardCharsets.UTF_8);
        moveAtomically(temporary, indexRoot.resolve("active.json"));
    }

    private void validateVersion(String version) {
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,200}")) {
            throw new IllegalArgumentException("Invalid recipe index version");
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Preserve the build failure; abandoned temporary versions are never activated.
                }
            });
        } catch (IOException ignored) {
            // Preserve the build failure; abandoned temporary versions are never activated.
        }
    }
}
