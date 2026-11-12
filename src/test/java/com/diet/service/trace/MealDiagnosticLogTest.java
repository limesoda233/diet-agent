package com.diet.service.trace;

import com.diet.exception.DietException;
import com.diet.model.SlotBundle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MealDiagnosticLogTest {
    @Test
    void recordsOnlyFixedSlotNamesAndCounts() {
        var slots = new SlotBundle(List.of("午餐"), List.of(), List.of(), List.of("清淡"),
                List.of(), List.of("清淡", "微辣"), List.of());

        assertThat(MealDiagnosticLog.slotCounts(slots))
                .isEqualTo("mealTime:1,mood:0,scene:0,healthGoal:1,cuisine:0,taste:2,convenience:0")
                .doesNotContain("午餐", "清淡", "微辣");
        assertThat(MealDiagnosticLog.slotCounts(null)).isEqualTo("none");
    }

    @Test
    void classifiesDraftFailuresWithoutLoggingExceptionText() {
        assertThat(MealDiagnosticLog.draftFailure(new DietException("餐食整理结果格式无效，请重试或手动录入")))
                .isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(MealDiagnosticLog.draftFailure(new DietException("餐食整理失败或超时，请重试或手动录入")))
                .isEqualTo("MODEL_UNAVAILABLE");
        assertThat(MealDiagnosticLog.draftFailure(new DietException("私人的过敏原和密钥")))
                .isEqualTo("OTHER");
    }
}
