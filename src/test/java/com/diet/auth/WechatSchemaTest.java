package com.diet.auth;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WechatSchemaTest {
    @Test
    void newWechatUserStartsAboveLegacyIdsWithAnEmptyPersonalLibrary() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat_schema;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/experience_schema.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/public_meal_seed.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/public_slot_seed.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO diet_user(display_name,status,role,created_at,updated_at) VALUES (NULL,'ACTIVE','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        Long userId = jdbc.queryForObject("SELECT id FROM diet_user", Long.class);
        assertThat(userId).isGreaterThanOrEqualTo(1_000_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PERSONAL'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meal_item WHERE source_type='PUBLIC' AND owner_user_id IS NULL", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM diet_slot_option", Integer.class)).isPositive();
    }

    @Test
    void oneWechatIdentityCannotBindTwoAccounts() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wechat_identity;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("CREATE TABLE diet_request_trace (id BIGINT PRIMARY KEY)");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migrations/007_wechat_access.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO diet_user(display_name,status,role,created_at,updated_at) VALUES (NULL,'ACTIVE','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO diet_user(display_name,status,role,created_at,updated_at) VALUES (NULL,'ACTIVE','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO user_identity(user_id,provider,app_id,provider_subject,created_at,last_login_at) VALUES (1000000,'WECHAT_MINI_PROGRAM','wx15b95618032f7270','same-openid',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_identity(user_id,provider,app_id,provider_subject,created_at,last_login_at) VALUES (1000001,'WECHAT_MINI_PROGRAM','wx15b95618032f7270','same-openid',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
