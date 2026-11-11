package com.diet.auth;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {
    private JdbcTemplate jdbc;
    private AuthService service;
    private String openid;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:auth_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/experience_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/public_meal_seed.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        openid = "owner-openid";
        service = new AuthService(new AuthRepository(jdbc), code -> openid,
                new TokenCodec(), new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                "wx15b95618032f7270", "owner-bootstrap-code",
                Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void bootstrapCreatesFreshAdminAndKnownIdentityCanLogInAgain() {
        var first = service.login("one-time-code", "owner-bootstrap-code");
        assertThat(first.user().id()).isGreaterThanOrEqualTo(1_000_000L);
        assertThat(first.user().role()).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PERSONAL'", Integer.class))
                .isZero();
        assertThat(service.authenticate(first.token()).userId()).isEqualTo(first.user().id());

        var second = service.login("another-code", null);
        assertThat(second.user().id()).isEqualTo(first.user().id());
        assertThat(second.token()).isNotEqualTo(first.token());
    }

    @Test
    void bootstrapInvitationCanOnlyCreateOneAdmin() {
        service.login("one-time-code", "owner-bootstrap-code");
        openid = "friend-openid";
        assertThatThrownBy(() -> service.login("another-code", "owner-bootstrap-code"))
                .isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void invitationCreatesSeparateFriendAndCannotBeReused() {
        var owner = service.login("one-time-code", "owner-bootstrap-code");
        jdbc.update("""
                INSERT INTO meal_item(source_type,owner_user_id,name,meal_time,mood,scene,health_goal,
                cuisine,taste,convenience,created_at,updated_at)
                VALUES ('PERSONAL',?,'owner-only','[]','[]','[]','[]','[]','[]','[]',NOW(),NOW())
                """, owner.user().id());
        String invitation = service.createInvitation(owner.user().id());
        openid = "friend-openid";
        var friend = service.login("friend-code", invitation);
        assertThat(friend.user().id()).isNotEqualTo(owner.user().id());
        assertThat(friend.user().role()).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PERSONAL' AND owner_user_id=?",
                Integer.class, friend.user().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PUBLIC' AND owner_user_id IS NULL",
                Integer.class)).isEqualTo(4);
        openid = "another-friend";
        assertThatThrownBy(() -> service.login("another-code", invitation)).isInstanceOf(AuthException.class);
    }

    @Test
    void logoutRevokesOnlyCurrentSession() {
        var first = service.login("one", "owner-bootstrap-code");
        var second = service.login("two", null);
        service.logout(service.authenticate(first.token()).sessionId());
        assertThatThrownBy(() -> service.authenticate(first.token())).isInstanceOf(AuthException.class);
        assertThat(service.authenticate(second.token()).userId()).isEqualTo(first.user().id());
    }

    @Test
    void disabledAccountCannotLogInAgain() {
        var owner = service.login("one", "owner-bootstrap-code");
        service.disableUser(owner.user().id());
        assertThatThrownBy(() -> service.login("two", null)).isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> service.authenticate(owner.token())).isInstanceOf(AuthException.class);
    }

    @Test
    void sessionLifetimeCanBeConfigured() {
        service.setSessionTtl(Duration.ofHours(2));
        var login = service.login("one", "owner-bootstrap-code");
        assertThat(login.expiresAt()).isEqualTo(Instant.parse("2026-11-10T02:00:00Z"));
    }
}
