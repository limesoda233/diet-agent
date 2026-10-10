package com.diet.service.jev;

import com.diet.enums.Intent;
import com.diet.model.ConversationTurn;
import com.diet.model.IntentResult;
import com.diet.model.SlotBundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JevIntentRecognitionService {
    private static final Map<String, String> INTENT_CRITERIA = Map.of(
            "MEAL_RECOMMENDATION", "用户请求一顿餐食的推荐或表达吃什么的需求",
            "CLARIFY_NEEDED", "用户有就餐倾向，但当前表达过于模糊，无法确定餐次或任何明确偏好",
            "MEAL_ADJUST", "用户要求调整、替换或排除最近一次已经给出的推荐",
            "MEAL_PLAN", "用户要求规划两顿及以上的餐食，例如早中晚或一周饮食",
            "RECIPE_QUERY", "用户询问一道具体菜品需要的食材、制作步骤或烹饪方法",
            "HEALTH_RISK", "涉及疾病诊断治疗、特殊人群高风险建议或极端节食",
            "OTHER", "与餐食推荐无关，或以上意图都不适用"
    );

    private final JevClient client;
    private final JevProperties properties;

    public JevIntentRecognitionService(JevClient client, JevProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean available() {
        return properties.configured() && properties.isIntentEnabled();
    }

    public boolean isAccepted(IntentResult result) {
        return result != null && Double.isFinite(result.confidence())
                && result.confidence() >= properties.getIntentConfidenceThreshold();
    }

    public IntentResult recognize(
            String userInput,
            SlotBundle knownSlots,
            List<ConversationTurn> recentHistory,
            Map<String, List<String>> slotOptions
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("currentMessage", userInput == null ? "" : userInput);
        state.put("knownSlots", slotState(knownSlots == null ? SlotBundle.empty() : knownSlots));
        state.put("recentHistory", historyState(recentHistory));

        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("intent", Map.of(
                "type", "choice",
                "instructions", "根据 `currentMessage` 判断本轮主要意图。仅用 `recentHistory` 消解‘换一个’等上下文指代。",
                "criteria", INTENT_CRITERIA
        ));

        Map<String, SlotQuestion> slotQuestions = new LinkedHashMap<>();
        slotOptions.forEach((slotName, values) -> {
            List<String> safeValues = values == null ? List.of() : values;
            for (int index = 0; index < safeValues.size(); index++) {
                String questionId = "slot_" + slotName + "_" + index;
                String option = safeValues.get(index);
                questions.put(questionId, Map.of(
                        "type", "noul",
                        "instructions", "`currentMessage` 是否明确表达了槽位 `" + slotName + "` 的值 `" + option
                                + "`？允许常见同义表达，但不要从 `knownSlots` 推断用户本轮没有说出的偏好。"
                ));
                slotQuestions.put(questionId, new SlotQuestion(slotName, option));
            }
        });

        JevResponse response = client.systemOne(state, questions);
        JevAnswer intentAnswer = requiredAnswer(response, "intent");
        if (!"choice".equals(intentAnswer.type()) || intentAnswer.choice() == null || intentAnswer.confidence() == null) {
            throw new JevClientException("jev_intent_answer_invalid");
        }
        Intent intent;
        try {
            intent = Intent.valueOf(intentAnswer.choice());
        } catch (IllegalArgumentException error) {
            throw new JevClientException("jev_intent_choice_invalid");
        }

        Map<String, List<String>> selected = new LinkedHashMap<>();
        slotQuestions.forEach((questionId, slotQuestion) -> {
            JevAnswer answer = response.answers().get(questionId);
            if (answer != null && "noul".equals(answer.type()) && answer.noul() != null
                    && answer.noul() >= properties.getSlotProbabilityThreshold()) {
                selected.computeIfAbsent(slotQuestion.slotName(), ignored -> new ArrayList<>()).add(slotQuestion.option());
            }
        });
        SlotBundle slots = new SlotBundle(
                selected.get("mealTime"),
                selected.get("mood"),
                selected.get("scene"),
                selected.get("healthGoal"),
                selected.get("cuisine"),
                selected.get("taste"),
                selected.get("convenience")
        );
        return new IntentResult(intent, slots, intentAnswer.confidence());
    }

    private JevAnswer requiredAnswer(JevResponse response, String id) {
        if (response == null || response.answers() == null || !response.answers().containsKey(id)) {
            throw new JevClientException("jev_answer_missing_" + id);
        }
        return response.answers().get(id);
    }

    private Map<String, List<String>> slotState(SlotBundle slots) {
        Map<String, List<String>> state = new LinkedHashMap<>();
        state.put("mealTime", slots.mealTime());
        state.put("mood", slots.mood());
        state.put("scene", slots.scene());
        state.put("healthGoal", slots.healthGoal());
        state.put("cuisine", slots.cuisine());
        state.put("taste", slots.taste());
        state.put("convenience", slots.convenience());
        return state;
    }

    private List<Map<String, Object>> historyState(List<ConversationTurn> history) {
        if (history == null) {
            return List.of();
        }
        return history.stream().map(turn -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", turn.role());
            item.put("intent", turn.intent() == null ? null : turn.intent().name());
            item.put("summary", turn.summary());
            return item;
        }).toList();
    }

    private record SlotQuestion(String slotName, String option) {
    }
}
