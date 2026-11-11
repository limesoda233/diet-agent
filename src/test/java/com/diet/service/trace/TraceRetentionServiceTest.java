package com.diet.service.trace;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRetentionServiceTest {
    @Test void removesExpiredTraceAndKeepsLegacyAndFutureTrace() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:retention_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/experience_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String value : new String[] {"past", "future", "legacy"}) {
            jdbc.update("""
                    INSERT INTO diet_request_trace(trace_id,session_id,user_id,status,event_count,trace_json,
                    created_at,updated_at,expires_at)
                    VALUES (?,'s',1000000,'SUCCESS',0,'{}',NOW(),NOW(),
                    CASE WHEN ?='past' THEN DATEADD('DAY',-1,NOW())
                         WHEN ?='future' THEN DATEADD('DAY',1,NOW()) ELSE NULL END)
                    """, value, value, value);
        }
        new TraceRetentionService(jdbc, 2).deleteExpired();
        assertThat(jdbc.queryForList("SELECT trace_id FROM diet_request_trace", String.class))
                .containsExactlyInAnyOrder("future", "legacy");
    }
}
