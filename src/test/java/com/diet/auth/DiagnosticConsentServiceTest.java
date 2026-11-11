package com.diet.auth;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticConsentServiceTest {
    @Test void oneConsentOnlyElevatesOneRequestFromItsOwner() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:consent_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE diet_request_trace(id BIGINT PRIMARY KEY)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO diet_user(status,role,created_at,updated_at) VALUES ('ACTIVE','USER',NOW(),NOW())");
        long owner = jdbc.queryForObject("SELECT id FROM diet_user", Long.class);
        var service = new DiagnosticConsentService(jdbc, 10);
        var consent = service.create(owner);
        assertThat(service.consume(owner + 1, consent.token(), "wrong-user")).isNull();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var attempts = List.of(
                    pool.submit(() -> service.consume(owner, consent.token(), "trace-1")),
                    pool.submit(() -> service.consume(owner, consent.token(), "trace-2")));
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get() != null) accepted++;
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.consume(owner, consent.token(), "again")).isNull();
        var revoked = service.create(owner);
        service.revoke(owner, revoked.id());
        assertThat(service.consume(owner, revoked.token(), "revoked")).isNull();
    }
}
