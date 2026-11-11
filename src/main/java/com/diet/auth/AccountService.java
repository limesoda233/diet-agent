package com.diet.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UserProfile profile(long userId) {
        List<UserProfile> rows = jdbc.query("""
                SELECT id,display_name,status,role FROM diet_user WHERE id=? AND status='ACTIVE'
                """, (row, ignored) -> new UserProfile(row.getLong(1), row.getString(2),
                row.getString(3), row.getString(4)), userId);
        if (rows.isEmpty()) throw new AuthException("RESOURCE_NOT_FOUND", "资源不存在");
        return rows.get(0);
    }

    public UserProfile rename(long userId, String name) {
        if (name != null && (name.isBlank() || name.length() > 64)) {
            throw new AuthException("INVALID_DISPLAY_NAME", "昵称需要 1 到 64 个字符");
        }
        jdbc.update("UPDATE diet_user SET display_name=?,updated_at=? WHERE id=? AND status='ACTIVE'",
                name, LocalDateTime.now(ZoneOffset.UTC), userId);
        return profile(userId);
    }

    @Transactional
    public void delete(long userId) {
        if ("ADMIN".equals(profile(userId).role()) && activeAdminCount() <= 1) {
            throw new AuthException("LAST_ADMIN", "请先创建另一位管理员");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now, userId);
        jdbc.update("UPDATE diagnostic_consent SET status='REVOKED' WHERE user_id=? AND status='AVAILABLE'", userId);
        deleteIfPresent("user_memory_feedback", userId);
        deleteIfPresent("user_memory_exposure", userId);
        deleteIfPresent("user_memory_event", userId);
        deleteIfPresent("user_memory_evidence", userId);
        deleteIfPresent("user_memory_job", userId);
        deleteIfPresent("user_memory", userId);
        deleteIfPresent("user_memory_profile", userId);
        deleteIfPresent("meal_plan_feedback", userId);
        if (tableExists("meal_plan_version") && tableExists("meal_plan")) {
            jdbc.update("DELETE FROM meal_plan_version WHERE plan_id IN (SELECT id FROM meal_plan WHERE user_id=?)", userId);
        }
        deleteIfPresent("meal_plan", userId);
        jdbc.update("DELETE FROM recommend_feedback WHERE user_id=?", userId);
        jdbc.update("DELETE FROM diet_request_trace WHERE user_id=?", userId);
        jdbc.update("DELETE FROM diet_messages WHERE session_id IN (SELECT id FROM diet_sessions WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM diet_sessions WHERE user_id=?", userId);
        jdbc.update("DELETE FROM meal_item WHERE source_type='PERSONAL' AND owner_user_id=?", userId);
        jdbc.update("DELETE FROM diagnostic_consent WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_session WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_identity WHERE user_id=?", userId);
        jdbc.update("UPDATE diet_user SET display_name=NULL,status='DELETION_PENDING',updated_at=? WHERE id=?", now, userId);
    }

    public int activeAdminCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM diet_user WHERE role='ADMIN' AND status='ACTIVE'", Integer.class);
    }

    private void deleteIfPresent(String table, long userId) {
        if (tableExists(table)) jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
    }

    private boolean tableExists(String table) {
        return jdbc.execute((java.sql.Connection connection) -> {
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, null)) {
                if (tables.next()) return true;
            }
            try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null,
                    table.toUpperCase(java.util.Locale.ROOT), null)) {
                return tables.next();
            }
        });
    }

    public record UserProfile(long id, String displayName, String status, String role) {}
}
