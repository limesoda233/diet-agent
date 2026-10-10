package com.diet.evaluation;

import com.diet.enums.Intent;
import com.diet.model.ConversationTurn;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class IntentEvaluationDataset {
    static final String RESOURCE = "evaluation/intent-eval.jsonl";

    private IntentEvaluationDataset() {
    }

    static List<Case> load(ObjectMapper objectMapper) {
        InputStream input = IntentEvaluationDataset.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("评测集不存在: " + RESOURCE);
        }

        List<Case> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    cases.add(objectMapper.readValue(line, Case.class));
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("读取意图评测集失败: " + error.getMessage(), error);
        }
        return List.copyOf(cases);
    }

    static Map<String, List<String>> slotOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("mealTime", List.of("早餐", "早午餐", "午餐", "下午茶", "晚餐", "夜宵", "加餐", "三餐"));
        options.put("mood", List.of("疲惫", "烦躁", "开心", "焦虑", "低落", "平静", "压力大", "没胃口", "想放松", "想奖励自己"));
        options.put("scene", List.of("工作", "校园", "家里", "周末", "加班", "运动后", "通勤", "聚餐", "独处", "旅行"));
        options.put("healthGoal", List.of("减脂", "清淡", "养胃", "高蛋白", "均衡", "降火", "低油", "低盐", "低糖", "补能", "增肌", "控碳水", "易消化", "暖胃"));
        options.put("cuisine", List.of("川菜", "粤菜", "湘菜", "江浙菜", "东北菜", "鲁菜", "闽南菜", "云南菜", "新疆菜", "轻食", "西餐", "日料", "韩餐", "东南亚菜", "火锅", "烧烤", "海鲜", "素食", "家常", "小吃", "粉面", "粥汤", "快餐", "甜品"));
        options.put("taste", List.of("辣", "微辣", "中辣", "麻辣", "甜", "酸甜", "咸鲜", "鲜香", "酱香", "蒜香", "番茄味", "咖喱味", "奶香", "油香", "烟火气"));
        options.put("convenience", List.of("快速", "慢享", "外带方便", "堂食舒服", "少排队", "少餐具", "一人食", "多人共享", "适合备餐", "适合边走边吃"));
        return Collections.unmodifiableMap(options);
    }

    record Case(
            String id,
            String slice,
            String utterance,
            List<History> history,
            boolean hasPreviousRecommendations,
            Intent expectedAgentIntent,
            Intent expectedFinalIntent
    ) {
        List<ConversationTurn> conversationHistory() {
            if (history == null || history.isEmpty()) {
                return List.of();
            }
            long timestamp = 1L;
            List<ConversationTurn> turns = new ArrayList<>();
            for (History turn : history) {
                turns.add(new ConversationTurn(turn.role(), turn.intent(), turn.summary(), timestamp++));
            }
            return List.copyOf(turns);
        }
    }

    record History(String role, Intent intent, String summary) {
    }
}
