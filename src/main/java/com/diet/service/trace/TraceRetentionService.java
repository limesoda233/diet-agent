package com.diet.service.trace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class TraceRetentionService {
    private final JdbcTemplate jdbc;
    private final int maxBatches;

    public TraceRetentionService(JdbcTemplate jdbc,
            @Value("${diet.trace.cleanup-max-batches:10}") int maxBatches) {
        this.jdbc = jdbc;
        this.maxBatches = maxBatches;
    }

    @Scheduled(fixedDelayString = "${diet.trace.cleanup-interval-ms:3600000}")
    public void deleteExpired() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = jdbc.update("""
                    DELETE FROM diet_request_trace WHERE expires_at < ? LIMIT 500
                    """, now);
            if (deleted < 500) break;
        }
        jdbc.update("""
                DELETE FROM diagnostic_consent
                 WHERE (status != 'CONSUMED' AND expires_at < ?)
                    OR (status='CONSUMED' AND consumed_at < ?)
                """, now, now.minusHours(24));
    }
}
