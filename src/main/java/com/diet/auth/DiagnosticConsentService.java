package com.diet.auth;

import com.diet.service.trace.DiagnosticConsentConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class DiagnosticConsentService implements DiagnosticConsentConsumer {
    private final JdbcTemplate jdbc;
    private final TokenCodec tokens = new TokenCodec();
    private final Duration ttl;

    @Autowired
    public DiagnosticConsentService(JdbcTemplate jdbc,
            @Value("${diet.diagnostics.consent-ttl:10m}") Duration ttl) {
        this.jdbc = jdbc;
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("diagnostic ttl must be positive");
        this.ttl = ttl;
    }

    public DiagnosticConsentService(JdbcTemplate jdbc, long ttlMinutes) {
        this(jdbc, Duration.ofMinutes(ttlMinutes));
    }

    public Consent create(long userId) {
        String id = UUID.randomUUID().toString();
        String token = tokens.newToken(32);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expires = now.plus(ttl);
        jdbc.update("""
                INSERT INTO diagnostic_consent(id,user_id,token_hash,status,expires_at,created_at)
                VALUES (?,?,?,'AVAILABLE',?,?)
                """, id, userId, tokens.hash(token), expires, now);
        return new Consent(id, token, expires);
    }

    @Override
    public String consume(long userId, String token, String traceId) {
        if (token == null || token.isBlank()) return null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<String> ids = jdbc.query("""
                SELECT id FROM diagnostic_consent
                 WHERE user_id=? AND token_hash=? AND status='AVAILABLE' AND expires_at>?
                """, (row, ignored) -> row.getString(1), userId, tokens.hash(token), now);
        if (ids.isEmpty()) return null;
        String id = ids.get(0);
        int changed = jdbc.update("""
                UPDATE diagnostic_consent SET status='CONSUMED',consumed_trace_id=?,consumed_at=?
                 WHERE id=? AND user_id=? AND status='AVAILABLE' AND expires_at>?
                """, traceId, now, id, userId, now);
        return changed == 1 ? id : null;
    }

    public void revoke(long userId, String id) {
        jdbc.update("""
                UPDATE diagnostic_consent SET status='REVOKED'
                 WHERE id=? AND user_id=? AND status='AVAILABLE'
                """, id, userId);
    }

    public record Consent(String id, String token, LocalDateTime expiresAt) {}
}
