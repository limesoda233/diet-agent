package com.diet.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {
    @Test void limitsRepeatedAttemptsPerRemoteAddress() {
        var limiter = new LoginRateLimiter(Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
        for (int attempt = 0; attempt < 30; attempt++) limiter.check("192.0.2.1");
        assertThatThrownBy(() -> limiter.check("192.0.2.1"))
                .isInstanceOf(AuthException.class);
        limiter.check("192.0.2.2");
    }
}
