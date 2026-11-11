package com.diet.service.session;

import com.diet.enums.SourceMode;
import com.diet.exception.DietException;
import com.diet.mapper.SessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SessionOwnershipTest {
    @Test
    void foreignSessionIdCannotBeCreatedForCurrentUser() {
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.findById("belongs-to-b", 1000001L)).thenReturn(null);
        when(mapper.countById("belongs-to-b")).thenReturn(1);
        SessionStateService states = new SessionStateService(mapper, new ObjectMapper());
        assertThatThrownBy(() -> states.loadOrCreate("belongs-to-b", 1000001L, SourceMode.PERSONAL))
                .isInstanceOf(DietException.class);
        verify(mapper, never()).insert(any());
    }
}
