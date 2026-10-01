package com.diet.service.recipe.rag.index;

import com.diet.service.recipe.rag.RecipeRagDependencyException;
import com.diet.service.recipe.rag.RecipeRagProperties;
import com.diet.service.recipe.rag.corpus.RecipeCorpusImporter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RecipeIndexCoordinator {
    private final RecipeCorpusImporter importer;
    private final RecipeSparseIndex sparseIndex;
    private final RecipeEmbeddingModel embeddingModel;
    private final RecipeVectorIndex vectorIndex;
    private final RecipeRagProperties properties;
    private final String parserVersion;

    public RecipeIndexCoordinator(RecipeCorpusImporter importer,
                                  RecipeSparseIndex sparseIndex,
                                  RecipeEmbeddingModel embeddingModel,
                                  RecipeVectorIndex vectorIndex,
                                  RecipeRagProperties properties,
                                  String parserVersion) {
        this.importer = importer;
        this.sparseIndex = sparseIndex;
        this.embeddingModel = embeddingModel;
        this.vectorIndex = vectorIndex;
        this.properties = properties;
        this.parserVersion = parserVersion;
    }

    public IndexBuildResult rebuild(Path corpusRoot) {
        var corpus = importer.importFrom(corpusRoot, properties.getSourceCommit());
        String version = version(corpus.manifest().corpusHash());
        String previousSparse = sparseIndex.activeVersion();
        String previousVector = vectorIndex.activeVersion();
        boolean sparseStaged = false;
        boolean vectorStaged = false;
        try {
            List<double[]> vectors = new ArrayList<>(corpus.chunks().size());
            for (var chunk : corpus.chunks()) {
                double[] vector = embeddingModel.embed(chunk.text()).block();
                if (vector == null || vector.length != embeddingModel.dimensions()) {
                    throw new RecipeRagDependencyException("embedding_dimension_mismatch");
                }
                vectors.add(vector);
            }
            sparseStaged = true;
            sparseIndex.stageVersion(version, corpus.documents(), corpus.chunks());
            vectorStaged = true;
            vectorIndex.stageVersion(version, corpus.chunks(), vectors);
            sparseIndex.activateVersion(version);
            vectorIndex.activateVersion(version);
            return new IndexBuildResult(version, corpus.manifest().corpusHash(),
                    corpus.documents().size(), corpus.chunks().size(), embeddingModel.modelName(),
                    embeddingModel.dimensions());
        } catch (RuntimeException error) {
            restore(previousSparse, previousVector);
            if (sparseStaged) discardSparse(version);
            if (vectorStaged) discardVector(version);
            throw new RecipeRagDependencyException("recipe_index_build_failed: " + error.getMessage(), error);
        }
    }

    public String activeVersion() {
        return sparseIndex.activeVersion();
    }

    private String version(String manifestHash) {
        return "recipe-" + manifestHash.substring(0, Math.min(16, manifestHash.length()))
                + "-" + safe(parserVersion) + "-" + safe(embeddingModel.modelName())
                + "-d" + embeddingModel.dimensions();
    }

    private String safe(String value) {
        String safe = value == null ? "unknown" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private void restore(String sparseVersion, String vectorVersion) {
        try {
            if (sparseVersion != null) sparseIndex.activateVersion(sparseVersion);
        } catch (RuntimeException ignored) {
        }
        try {
            if (vectorVersion != null) vectorIndex.activateVersion(vectorVersion);
        } catch (RuntimeException ignored) {
        }
    }

    private void discardSparse(String version) {
        try { sparseIndex.discardVersion(version); } catch (RuntimeException ignored) { }
    }

    private void discardVector(String version) {
        try { vectorIndex.discardVersion(version); } catch (RuntimeException ignored) { }
    }

    public record IndexBuildResult(String indexVersion, String corpusHash, int documentCount,
                                   int chunkCount, String embeddingModel, int dimensions) {
    }
}
