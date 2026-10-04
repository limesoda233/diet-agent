package com.diet.evaluation.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class RecipeRagasExporter {
    private final ObjectWriter json;

    RecipeRagasExporter(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json").writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    void write(Path output, List<RecipeRagasSample> samples) throws IOException {
        Objects.requireNonNull(output, "output");
        List<RecipeRagasSample> validated = validated(samples);
        Path destination = output.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + ".", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                for (RecipeRagasSample sample : validated) {
                    writer.write(json.writeValueAsString(sample));
                    writer.newLine();
                }
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<RecipeRagasSample> validated(List<RecipeRagasSample> samples) {
        if (samples == null || samples.isEmpty()) throw new IllegalArgumentException("samples must not be empty");
        Set<String> queryIds = new HashSet<>();
        for (RecipeRagasSample sample : samples) {
            if (sample == null) throw new IllegalArgumentException("samples must not contain null");
            if (!queryIds.add(sample.queryId())) throw new IllegalArgumentException("Duplicate query_id: " + sample.queryId());
        }
        return List.copyOf(samples);
    }
}
