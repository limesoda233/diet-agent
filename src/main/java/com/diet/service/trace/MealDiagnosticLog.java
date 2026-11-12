package com.diet.service.trace;

import com.diet.model.SlotBundle;

/** Fixed-field diagnostic values; never include message text, meal names or user identifiers. */
public final class MealDiagnosticLog {
    private MealDiagnosticLog() { }

    public static String slotCounts(SlotBundle slots) {
        if (slots == null) return "none";
        return "mealTime:"+slots.mealTime().size()+",mood:"+slots.mood().size()
                +",scene:"+slots.scene().size()+",healthGoal:"+slots.healthGoal().size()
                +",cuisine:"+slots.cuisine().size()+",taste:"+slots.taste().size()
                +",convenience:"+slots.convenience().size();
    }

    public static String traceId(AgentTraceService trace) {
        return trace == null ? "-" : trace.currentTraceId();
    }

    /** Maps only known server-owned messages; arbitrary exception text never reaches application logs. */
    public static String draftFailure(Throwable error) {
        if (error == null || error.getMessage() == null) return "OTHER";
        return switch (error.getMessage()) {
            case "餐食整理失败或超时，请重试或手动录入" -> "MODEL_UNAVAILABLE";
            case "餐食整理未返回有效结果，请重试或手动录入" -> "EMPTY_MODEL_OUTPUT";
            case "餐食整理结果格式无效，请重试或手动录入" -> "INVALID_MODEL_OUTPUT";
            case "没有提取到餐食信息，请补充描述或手动录入" -> "NO_FIELDS";
            case "没有提取到有原文依据的餐食信息，请补充描述或手动录入" -> "NO_EVIDENCE";
            case "草稿请求只允许提供文本字段text" -> "INVALID_REQUEST";
            default -> "OTHER";
        };
    }
}
