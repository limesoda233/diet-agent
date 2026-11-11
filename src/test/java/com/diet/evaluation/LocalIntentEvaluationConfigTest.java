package com.diet.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalIntentEvaluationConfigTest {
    @TempDir Path directory;

    @Test
    void loadsAllowedSettingsWithoutExecutingShellExpressionsOrReadingDatabaseSecrets() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "\uFEFF# local\nexport DEEPSEEK_API_KEY='literal-$(test)'\n"
                + "JEV_API_KEY=\"jev-fixture\" # note\nDB_PASSWORD=must-not-be-imported\n");
        var config = LocalIntentEvaluationConfig.load(file, Map.of("JEV_API_KEY", "environment-fixture"));
        assertThat(config.value("DEEPSEEK_API_KEY", "")).isEqualTo("literal-$(test)");
        assertThat(config.value("JEV_API_KEY", "")).isEqualTo("environment-fixture");
        assertThat(config.value("DB_PASSWORD", "ignored")).isEqualTo("ignored");
        assertThat(config.toString()).doesNotContain("literal-", "environment-fixture", "must-not");
    }

    @Test
    void malformedSecretLineReportsLineNumberWithoutExposingItsValue() throws Exception {
        Path file = directory.resolve(".env");
        Files.writeString(file, "JEV_API_KEY=\"private-unclosed\n");
        assertThatThrownBy(() -> LocalIntentEvaluationConfig.load(file, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1")
                .hasMessageNotContaining("private-unclosed");
    }
}
