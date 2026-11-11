package com.diet.auth;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountServiceTest {
    @Test void lastActiveAdminCannotDeleteOwnAccount() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:last_admin_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/experience_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO diet_user(status,role,created_at,updated_at) VALUES ('ACTIVE','ADMIN',NOW(),NOW())");
        assertThatThrownBy(() -> new AccountService(jdbc).delete(1_000_000L))
                .isInstanceOf(AuthException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM diet_user WHERE id=1000000", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test void deletingAccountRemovesOnlyItsPrivateDataAndKeepsPublicMeals() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:account_" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/experience_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/public_meal_seed.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (int i = 0; i < 2; i++) jdbc.update(
                "INSERT INTO diet_user(status,role,created_at,updated_at) VALUES ('ACTIVE','USER',NOW(),NOW())");
        long owner = 1_000_000L;
        long other = owner + 1;
        jdbc.update("""
                INSERT INTO meal_item(source_type,owner_user_id,name,meal_time,mood,scene,health_goal,
                cuisine,taste,convenience,created_at,updated_at)
                VALUES ('PERSONAL',?,'私有餐食','[]','[]','[]','[]','[]','[]','[]',NOW(),NOW())
                """, owner);
        jdbc.update("""
                INSERT INTO meal_item(source_type,owner_user_id,name,meal_time,mood,scene,health_goal,
                cuisine,taste,convenience,created_at,updated_at)
                VALUES ('PERSONAL',?,'其他人的餐食','[]','[]','[]','[]','[]','[]','[]',NOW(),NOW())
                """, other);
        jdbc.update("INSERT INTO diet_sessions(id,user_id,phase,slots,last_recommendations,created_at,updated_at) VALUES ('owner-session',?,'START','{}','[]',NOW(),NOW())", owner);
        jdbc.update("INSERT INTO diet_messages(session_id,role,content,created_at) VALUES ('owner-session','user','private',NOW())");
        jdbc.update("INSERT INTO recommend_feedback(user_id,session_id,action,created_at) VALUES (?,'owner-session','LIKE',NOW())", owner);
        jdbc.update("INSERT INTO diet_request_trace(trace_id,session_id,user_id,status,event_count,trace_json,created_at,updated_at) VALUES ('trace','owner-session',?,'SUCCESS',0,'{}',NOW(),NOW())", owner);
        jdbc.update("INSERT INTO user_identity(user_id,provider,app_id,provider_subject,created_at,last_login_at) VALUES (?,'WECHAT_MINI_PROGRAM','app','owner-openid',NOW(),NOW())", owner);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status -> {
            new AccountService(jdbc).delete(owner);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PERSONAL' AND owner_user_id=?", Integer.class, owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PERSONAL' AND owner_user_id=?", Integer.class, other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PUBLIC'", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_request_trace", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM diet_user WHERE id=?", String.class, owner)).isEqualTo("DELETION_PENDING");
    }
}
