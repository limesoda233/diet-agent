package com.diet.service.session;

import com.diet.enums.SourceMode;
import com.diet.mapper.SessionMapper;
import com.diet.model.SessionRow;
import com.diet.model.SessionState;
import com.diet.model.SlotBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionSourceModeTest {
    @Test
    void changingSourceStartsFreshSessionWithoutReusingOldFilters() {
        var mapper = mock(SessionMapper.class);
        var service = new SessionStateService(mapper, new ObjectMapper());
        when(mapper.update(any())).thenReturn(1);
        service.save(SessionState.fresh("personal-session", 7L, SourceMode.PERSONAL)
                .withSlots(new SlotBundle(List.of("晚餐"), List.of(), List.of(), List.of("清淡"),
                        List.of(), List.of("清淡"), List.of())));
        var old = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(old.capture());
        when(mapper.findById("personal-session", 7L)).thenReturn(old.getValue());

        var next = service.loadOrCreate("personal-session", 7L, SourceMode.PUBLIC);

        assertThat(next.sourceMode()).isEqualTo(SourceMode.PUBLIC);
        assertThat(next.sessionId()).isNotEqualTo("personal-session");
        assertThat(next.slots().isEmpty()).isTrue();
        verify(mapper).insert(any(SessionRow.class));
        verify(mapper, times(1)).update(any(SessionRow.class));
    }

    @Test
    void draftActionCannotCrossToAnotherSourceMode() {
        var mapper = mock(SessionMapper.class);
        var service = new SessionStateService(mapper, new ObjectMapper());
        when(mapper.update(any())).thenReturn(1);
        service.save(SessionState.fresh("personal-session", 7L, SourceMode.PERSONAL));
        var old = ArgumentCaptor.forClass(SessionRow.class);
        verify(mapper).update(old.capture());
        when(mapper.findById("personal-session", 7L)).thenReturn(old.getValue());

        assertThat(service.loadExisting("personal-session", 7L, SourceMode.PUBLIC)).isNull();
        verify(mapper, never()).insert(any());
    }
}
