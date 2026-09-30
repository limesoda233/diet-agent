package com.diet.service.meal.constraint;

import com.diet.enums.Intent;
import com.diet.enums.SessionPhase;
import com.diet.enums.SourceMode;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionRow;
import com.diet.model.SessionState;
import com.diet.model.SlotBundle;
import com.diet.service.session.SessionStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MealConstraintSessionTest {
    @Test
    void readsLegacyRetrievalJsonAndWritesMealConstraintContext() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionStateService service = new SessionStateService(mapper, new ObjectMapper());
        when(mapper.update(any())).thenReturn(1);
        var context = new MealConstraintContext("热汤",
                new MealConstraints(null, List.of("牛肉"), null, null), "rule")
                .pending("必须含西虹柿");
        var state = SessionState.fresh("s", 1L, SourceMode.PUBLIC)
                .withMealConstraintContext(context)
                .withSlots(SlotBundle.empty())
                .withPhase(SessionPhase.START)
                .withIntent(Intent.MEAL_ADJUST)
                .appendLastRecommendations(List.of(1L));

        service.save(state);
        var capture = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(capture.capture());
        assertThat(capture.getValue().getSlots()).contains("mealConstraintContext");

        String legacy = capture.getValue().getSlots().replace("mealConstraintContext", "retrieval");
        capture.getValue().setSlots(legacy);
        when(mapper.findById("s", 1L)).thenReturn(capture.getValue());
        assertThat(service.loadOrCreate("s", 1L, SourceMode.PUBLIC).mealConstraintContext())
                .isEqualTo(context);
    }
}
