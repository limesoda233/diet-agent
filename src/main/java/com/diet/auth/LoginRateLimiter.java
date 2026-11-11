package com.diet.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter() { this(Clock.systemUTC()); }
    LoginRateLimiter(Clock clock) { this.clock = clock; }

    public void check(String remoteAddress) {
        long now = clock.millis();
        String key = remoteAddress == null ? "unknown" : remoteAddress;
        if (windows.size() > 10_000) windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > 60_000);
        Window window = windows.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.startedAt >= 60_000) return new Window(now, 1);
            return new Window(previous.startedAt, previous.count + 1);
        });
        if (window.count > 30) throw new AuthException("RATE_LIMITED", "登录过于频繁，请稍后再试");
    }

    private record Window(long startedAt, int count) {}
}
