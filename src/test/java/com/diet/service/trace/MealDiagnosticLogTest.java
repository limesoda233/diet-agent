package com.diet.service.trace;

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
}
