package com.diet.evaluation;

import java.util.List;

final class IntentRoutingStatsCollector {
    private int jevDecisions;
    private int jevLowConfidenceFallbacks;
    private int jevExceptionFallbacks;
    private int deepSeekCalls;
    private int ruleFallbacks;

    IntentDecisionSource recordCase(List<IntentRoutingSignal> signals, boolean ruleFallback) {
        List<IntentRoutingSignal> safeSignals = signals == null ? List.of() : signals;
        for (IntentRoutingSignal signal : safeSignals) {
            switch (signal) {
                case JEV_DECISION -> jevDecisions++;
                case JEV_LOW_CONFIDENCE_FALLBACK -> jevLowConfidenceFallbacks++;
                case JEV_EXCEPTION_FALLBACK -> jevExceptionFallbacks++;
                case DEEPSEEK_CALL -> deepSeekCalls++;
            }
        }
        if (ruleFallback) {
            ruleFallbacks++;
            return IntentDecisionSource.RULE_FALLBACK;
        }
        if (safeSignals.contains(IntentRoutingSignal.DEEPSEEK_CALL)) {
            return IntentDecisionSource.DEEPSEEK;
        }
        if (safeSignals.contains(IntentRoutingSignal.JEV_DECISION)) {
            return IntentDecisionSource.JEV;
        }
        return IntentDecisionSource.UNKNOWN;
    }

    IntentRoutingStats snapshot() {
        int jevAttempts = jevDecisions + jevExceptionFallbacks;
        int jevAccepted = Math.max(0, jevDecisions - jevLowConfidenceFallbacks);
        double acceptanceRate = jevAttempts == 0
                ? 0.0
                : Math.round((double) jevAccepted * 10_000.0 / jevAttempts) / 100.0;
        return new IntentRoutingStats(
                jevAttempts,
                jevAccepted,
                acceptanceRate,
                jevLowConfidenceFallbacks,
                jevExceptionFallbacks,
                deepSeekCalls,
                ruleFallbacks
        );
    }
}

enum IntentRoutingSignal {
    JEV_DECISION,
    JEV_LOW_CONFIDENCE_FALLBACK,
    JEV_EXCEPTION_FALLBACK,
    DEEPSEEK_CALL
}

enum IntentDecisionSource {
    JEV,
    DEEPSEEK,
    RULE_FALLBACK,
    UNKNOWN
}

record IntentRoutingStats(
        int jevAttempts,
        int jevAccepted,
        double jevAcceptanceRate,
        int jevLowConfidenceFallbacks,
        int jevExceptionFallbacks,
        int deepSeekCalls,
        int ruleFallbacks
) {
}
