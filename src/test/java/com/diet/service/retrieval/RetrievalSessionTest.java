package com.diet.service.retrieval;

import com.diet.enums.*;
import com.diet.mapper.SessionMapper;
import com.diet.model.*;
import com.diet.service.session.SessionStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalSessionTest {
    @Test void persistsConstraintsAndKeepsThemAcrossStateTransitions() {
        var mapper = mock(SessionMapper.class);
        var service = new SessionStateService(mapper, new ObjectMapper());
        when(mapper.update(any())).thenReturn(1);
        var context = new RetrievalContext("热汤", new MealConstraints(null, List.of("牛肉"), null, null), "rule").pending("必须含西虹柿");
        var state = SessionState.fresh("s", 1L, SourceMode.PUBLIC).withRetrieval(context)
                .withSlots(SlotBundle.empty()).withPhase(SessionPhase.START).withIntent(Intent.MEAL_ADJUST)
                .appendLastRecommendations(List.of(1L)).withSourceMode(SourceMode.PUBLIC);
        service.save(state);
        var capture = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(capture.capture());
        when(mapper.findById("s", 1L)).thenReturn(capture.getValue());
        assertThat(service.loadOrCreate("s", 1L, SourceMode.PUBLIC).retrieval()).isEqualTo(context);
        capture.getValue().setSlots("{}");
        assertThat(service.loadOrCreate("s", 1L, SourceMode.PUBLIC).retrieval()).isEqualTo(RetrievalContext.empty());
    }
}
