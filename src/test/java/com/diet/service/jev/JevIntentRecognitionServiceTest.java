package com.diet.service.jev;

import com.diet.enums.Intent;
import com.diet.model.ConversationTurn;
import com.diet.model.IntentResult;
import com.diet.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JevIntentRecognitionServiceTest {

    @Test
    void mapsChoiceAndIndependentSlotJudgmentsIntoIntentResult() {
        AtomicReference<Object> capturedState = new AtomicReference<>();
        AtomicReference<Map<String, Object>> capturedQuestions = new AtomicReference<>();
        JevClient client = (state, questions) -> {
            capturedState.set(state);
            capturedQuestions.set(questions);
            return response(Map.of(
                    "intent", choice("MEAL_RECOMMENDATION", 0.87),
                    "slot_mealTime_0", noul(0.91),
                    "slot_mealTime_1", noul(0.02),
                    "slot_healthGoal_0", noul(0.83),
                    "slot_healthGoal_1", noul(0.78),
                    "slot_taste_0", noul(0.40)
            ));
        };
        JevProperties properties = configuredProperties();
        properties.setSlotProbabilityThreshold(0.70);
        JevIntentRecognitionService service = new JevIntentRecognitionService(client, properties);
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("mealTime", List.of("晚餐", "午餐"));
        options.put("healthGoal", List.of("清淡", "高蛋白"));
        options.put("taste", List.of("微辣"));

        IntentResult result = service.recognize(
                "晚上加班，推荐清淡高蛋白的",
                SlotBundle.empty(),
                List.of(new ConversationTurn("assistant", Intent.MEAL_RECOMMENDATION, "推荐过鸡胸饭", 1L)),
                options
        );

        assertThat(result.intent()).isEqualTo(Intent.MEAL_RECOMMENDATION);
        assertThat(result.confidence()).isEqualTo(0.87);
        assertThat(result.slots().mealTime()).containsExactly("晚餐");
        assertThat(result.slots().healthGoal()).containsExactly("清淡", "高蛋白");
        assertThat(result.slots().taste()).isEmpty();
        assertThat(capturedState.get().toString()).contains("晚上加班").contains("推荐过鸡胸饭");
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) capturedState.get();
        assertThat((List<?>) state.get("recentHistory")).first().isInstanceOf(Map.class);
        assertThat(state.get("knownSlots")).isInstanceOf(Map.class);
        assertThat(capturedQuestions.get()).containsKeys(
                "intent", "slot_mealTime_0", "slot_mealTime_1", "slot_healthGoal_0", "slot_healthGoal_1", "slot_taste_0"
        );
    }

    @Test
    void acceptsOnlyConfiguredHighConfidenceIntentResults() {
        JevProperties properties = configuredProperties();
        properties.setIntentConfidenceThreshold(0.70);
        JevIntentRecognitionService service = new JevIntentRecognitionService(
                (state, questions) -> response(Map.of("intent", choice("OTHER", 0.69))),
                properties
        );

        assertThat(service.available()).isTrue();
        assertThat(service.isAccepted(new IntentResult(Intent.OTHER, SlotBundle.empty(), 0.69))).isFalse();
        assertThat(service.isAccepted(new IntentResult(Intent.OTHER, SlotBundle.empty(), 0.70))).isTrue();
    }

    private static JevProperties configuredProperties() {
        JevProperties properties = new JevProperties();
        properties.setApiKey("test-key");
        properties.setEnabled(true);
        properties.setIntentEnabled(true);
        return properties;
    }

    private static JevResponse response(Map<String, JevAnswer> answers) {
        return new JevResponse("jev-test", answers, new JevUsage(10, 2));
    }

    private static JevAnswer choice(String value, double confidence) {
        return new JevAnswer("choice", value, null, null, confidence, Map.of(value, 1.0));
    }

    private static JevAnswer noul(double probability) {
        return new JevAnswer("noul", null, null, probability, null, Map.of());
    }
}
