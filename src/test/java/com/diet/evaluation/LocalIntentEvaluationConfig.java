package com.diet.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Only the opt-in evaluation reads this file. Values are literal, never shell-evaluated or logged. */
final class LocalIntentEvaluationConfig {
    private static final Set<String> ALLOWED = Set.of("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL",
            "DEEPSEEK_LIGHT_MODEL", "JEV_API_KEY", "JEV_ENDPOINT", "JEV_MODEL", "JEV_TIMEOUT",
            "JEV_INTENT_CONFIDENCE_THRESHOLD", "JEV_SLOT_PROBABILITY_THRESHOLD");
    private final Map<String, String> values;

    private LocalIntentEvaluationConfig(Map<String, String> values) { this.values = Map.copyOf(values); }

    static LocalIntentEvaluationConfig load(Path file, Map<String, String> environment) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (Files.exists(file)) {
            int number = 0;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                number++;
                String line = raw.replace("\uFEFF", "").trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int equals = line.indexOf('=');
                if (line.startsWith("#") || equals < 1) continue;
                String name = line.substring(0, equals).trim();
                if (!ALLOWED.contains(name)) continue;
                String value = line.substring(equals + 1).trim();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    int close = value.indexOf(value.charAt(0), 1);
                    if (close < 0 || (!value.substring(close + 1).trim().isEmpty()
                            && !value.substring(close + 1).trim().startsWith("#"))) {
                        throw new IllegalArgumentException("Invalid evaluation setting at line " + number);
                    }
                    value = value.substring(1, close);
                } else {
                    value = value.replaceFirst("\\s+#.*$", "").trim();
                }
                values.put(name, value);
            }
        }
        // Explicit process environment wins over the local file; DB settings are never imported.
        for (String name : ALLOWED) if (environment.containsKey(name)) values.put(name, environment.get(name));
        return new LocalIntentEvaluationConfig(values);
    }

    String value(String name, String fallback) { return values.getOrDefault(name, fallback); }
    @Override public String toString() { return "LocalIntentEvaluationConfig[values redacted]"; }
}
