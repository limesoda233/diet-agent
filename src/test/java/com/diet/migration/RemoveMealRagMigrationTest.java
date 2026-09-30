package com.diet.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RemoveMealRagMigrationTest {

    @Test
    void migrationDropsOnlyDerivedMealRagTables() throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/migrations/004_remove_meal_rag.sql")) {
            assertThat(stream).as("meal RAG removal migration").isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains("drop table if exists meal_index_progress")
                    .contains("drop table if exists meal_index_task")
                    .doesNotContain("drop table if exists meal_item")
                    .doesNotContain("drop table if exists meal_knowledge")
                    .doesNotContain("truncate");
        }
    }
}
