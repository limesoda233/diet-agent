package com.diet.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRoutingStatsCollectorTest {

    @Test
    void summarizesAcceptedLowConfidenceAndExceptionRoutes() {
        IntentRoutingStatsCollector collector = new IntentRoutingStatsCollector();

        IntentDecisionSource accepted = collector.recordCase(
                List.of(IntentRoutingSignal.JEV_DECISION),
                false
        );
        IntentDecisionSource lowConfidence = collector.recordCase(
                List.of(
                        IntentRoutingSignal.JEV_DECISION,
                        IntentRoutingSignal.JEV_LOW_CONFIDENCE_FALLBACK,
                        IntentRoutingSignal.DEEPSEEK_CALL
                ),
                false
        );
        IntentDecisionSource exceptionFallback = collector.recordCase(
                List.of(
                        IntentRoutingSignal.JEV_EXCEPTION_FALLBACK,
                        IntentRoutingSignal.DEEPSEEK_CALL
                ),
                true
        );

        IntentRoutingStats stats = collector.snapshot();
        assertThat(accepted).isEqualTo(IntentDecisionSource.JEV);
        assertThat(lowConfidence).isEqualTo(IntentDecisionSource.DEEPSEEK);
        assertThat(exceptionFallback).isEqualTo(IntentDecisionSource.RULE_FALLBACK);
        assertThat(stats.jevAttempts()).isEqualTo(3);
        assertThat(stats.jevAccepted()).isEqualTo(1);
        assertThat(stats.jevAcceptanceRate()).isEqualTo(33.33);
        assertThat(stats.jevLowConfidenceFallbacks()).isEqualTo(1);
        assertThat(stats.jevExceptionFallbacks()).isEqualTo(1);
        assertThat(stats.deepSeekCalls()).isEqualTo(2);
        assertThat(stats.ruleFallbacks()).isEqualTo(1);
    }

    @Test
    void marksDirectDeepSeekRouteWhenJevIsUnavailable() {
        IntentRoutingStatsCollector collector = new IntentRoutingStatsCollector();

        IntentDecisionSource source = collector.recordCase(
                List.of(IntentRoutingSignal.DEEPSEEK_CALL),
                false
        );

        assertThat(source).isEqualTo(IntentDecisionSource.DEEPSEEK);
        assertThat(collector.snapshot().jevAttempts()).isZero();
        assertThat(collector.snapshot().deepSeekCalls()).isEqualTo(1);
    }
}
