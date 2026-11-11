package com.diet.service.trace;

import com.diet.mapper.AgentTraceMapper;
import com.diet.model.RequestTraceRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TracePrivacyTest {
    @Test
    void metadataModeDiscardsMessagesModelOutputAndExceptionTextBeforePersistence() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceService trace = new AgentTraceService(mapper, new ObjectMapper(), null, "METADATA");
        String secret = "my-health-data-and-secret-123";
        try (var ignored = trace.openTrace("trace-1", "session-1", 1_000_001L)) {
            trace.recordEvent("REQUEST_RECEIVED", "HTTP", secret, secret);
            trace.recordError("REQUEST_FAILED", "HTTP", secret, new IllegalStateException(secret));
        }
        ArgumentCaptor<RequestTraceRow> saved = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getTraceJson()).contains("REQUEST_RECEIVED", "REQUEST_FAILED")
                .doesNotContain(secret);
        assertThat(saved.getValue().getErrorMessage()).doesNotContain(secret);
        assertThat(saved.getValue().getCaptureMode()).isEqualTo("METADATA");
        assertThat(saved.getValue().getExpiresAt()).isBetween(
                LocalDateTime.now(ZoneOffset.UTC).plusDays(14).minusMinutes(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(14).plusMinutes(1));
    }

    @Test
    void offModeDoesNotPersistTrace() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        AgentTraceService trace = new AgentTraceService(mapper, new ObjectMapper(), null, "OFF");
        try (var ignored = trace.openTrace("trace-1", "session-1", 1_000_001L)) {
            trace.recordEvent("REQUEST_RECEIVED", "HTTP", "secret", "secret");
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void validConsentElevatesOnlyOneRequest() {
        AgentTraceMapper mapper = mock(AgentTraceMapper.class);
        DiagnosticConsentConsumer consent = mock(DiagnosticConsentConsumer.class);
        when(consent.consume(1_000_001L, "one-use", "trace-1")).thenReturn("consent-id");
        AgentTraceService trace = new AgentTraceService(mapper, new ObjectMapper(), consent, "METADATA");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Diet-Diagnostic-Consent", "one-use");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            try (var ignored = trace.openTrace("trace-1", "session-1", 1_000_001L)) {
                trace.recordEvent("REQUEST_RECEIVED", "HTTP", "private-text", null);
            }
            try (var ignored = trace.openTrace("trace-2", "session-1", 1_000_001L)) {
                trace.recordEvent("REQUEST_RECEIVED", "HTTP", "private-text", null);
            }
        } finally { RequestContextHolder.resetRequestAttributes(); }
        ArgumentCaptor<RequestTraceRow> saved = ArgumentCaptor.forClass(RequestTraceRow.class);
        verify(mapper, times(2)).insert(saved.capture());
        assertThat(saved.getAllValues().get(0).getCaptureMode()).isEqualTo("FULL");
        assertThat(saved.getAllValues().get(0).getExpiresAt()).isBetween(
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1).minusMinutes(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1).plusMinutes(1));
        assertThat(saved.getAllValues().get(0).getTraceJson()).contains("private-text");
        assertThat(saved.getAllValues().get(1).getCaptureMode()).isEqualTo("METADATA");
        assertThat(saved.getAllValues().get(1).getTraceJson()).doesNotContain("private-text");
    }
}
