package com.diet.service.trace;

public interface DiagnosticConsentConsumer {
    String consume(long userId, String token, String traceId);
}
