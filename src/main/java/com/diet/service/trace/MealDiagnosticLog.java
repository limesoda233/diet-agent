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
}
