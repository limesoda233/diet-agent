package com.diet.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public User findIdentity(String appId, String openid) {
        List<User> users = jdbc.query("""
                SELECT u.id, u.display_name, u.status, u.role
                  FROM user_identity i JOIN diet_user u ON u.id=i.user_id
                 WHERE i.provider='WECHAT_MINI_PROGRAM' AND i.app_id=? AND i.provider_subject=?
                """, (row, ignored) -> new User(row.getLong("id"), row.getString("display_name"),
                row.getString("status"), row.getString("role")), appId, openid);
        return users.isEmpty() ? null : users.get(0);
    }

    public long createUser(String role, LocalDateTime now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO diet_user(display_name,status,role,created_at,updated_at) VALUES (NULL,'ACTIVE',?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, role);
            statement.setObject(2, now);
            statement.setObject(3, now);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public void bindIdentity(long userId, String appId, String openid, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO user_identity(user_id,provider,app_id,provider_subject,created_at,last_login_at)
                VALUES (?,'WECHAT_MINI_PROGRAM',?,?,?,?)
                """, userId, appId, openid, now, now);
    }

    public void touchIdentity(String appId, String openid, LocalDateTime now) {
        jdbc.update("""
                UPDATE user_identity SET last_login_at=?
                 WHERE provider='WECHAT_MINI_PROGRAM' AND app_id=? AND provider_subject=?
                """, now, appId, openid);
    }

    public void ensureBootstrap(byte[] hash, LocalDateTime expiry, LocalDateTime now) {
        Integer admins = jdbc.queryForObject(
                "SELECT COUNT(*) FROM diet_user WHERE role='ADMIN'", Integer.class);
        if (admins == null || admins != 0) return;
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_invitation WHERE code_hash=?", Integer.class, hash);
        if (existing != null && existing != 0) return;
        jdbc.update("""
                INSERT INTO user_invitation(id,code_hash,status,target_role,expires_at,created_at)
                VALUES (?,?,'AVAILABLE','ADMIN',?,?)
                """, UUID.randomUUID().toString(), hash, expiry, now);
    }

    public Invitation findAvailableInvitation(byte[] hash, LocalDateTime now) {
        List<Invitation> invitations = jdbc.query("""
                SELECT id,target_role FROM user_invitation
                 WHERE code_hash=? AND status='AVAILABLE' AND expires_at>?
                """, (row, ignored) -> new Invitation(row.getString("id"), row.getString("target_role")),
                hash, now);
        return invitations.isEmpty() ? null : invitations.get(0);
    }

    public boolean claimInvitation(String id, long userId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE user_invitation SET status='CLAIMED',claimed_by=?,claimed_at=?
                 WHERE id=? AND status='AVAILABLE' AND expires_at>?
                """, userId, now, id, now) == 1;
    }

    public void createInvitation(byte[] hash, long adminId, LocalDateTime expiry, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO user_invitation(id,code_hash,status,target_role,expires_at,created_by,created_at)
                VALUES (?,?,'AVAILABLE','USER',?,?,?)
                """, UUID.randomUUID().toString(), hash, expiry, adminId, now);
    }

    public void createSession(String sessionId, long userId, byte[] hash,
                              LocalDateTime expiry, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO user_session(id,user_id,token_hash,expires_at,created_at,last_seen_at)
                VALUES (?,?,?,?,?,?)
                """, sessionId, userId, hash, expiry, now, now);
    }

    public AuthPrincipal findSession(byte[] hash, LocalDateTime now) {
        List<AuthPrincipal> principals = jdbc.query("""
                SELECT s.id,u.id AS user_id,u.role
                  FROM user_session s JOIN diet_user u ON u.id=s.user_id
                 WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?
                   AND u.status='ACTIVE'
                """, (row, ignored) -> new AuthPrincipal(
                row.getLong("user_id"), row.getString("role"), row.getString("id")), hash, now);
        return principals.isEmpty() ? null : principals.get(0);
    }

    public void revokeSession(String sessionId, LocalDateTime now) {
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE id=? AND revoked_at IS NULL",
                now, sessionId);
    }

    public void disableUser(long userId, LocalDateTime now) {
        jdbc.update("UPDATE diet_user SET status='DISABLED',updated_at=? WHERE id=? AND status='ACTIVE'", now, userId);
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                now, userId);
    }

    public void enableUser(long userId, LocalDateTime now) {
        jdbc.update("UPDATE diet_user SET status='ACTIVE',updated_at=? WHERE id=? AND status='DISABLED'", now, userId);
    }

    public List<User> listUsers() {
        return jdbc.query("SELECT id,display_name,status,role FROM diet_user WHERE id>=1000000 ORDER BY id",
                (row, ignored) -> new User(row.getLong(1), row.getString(2),
                        row.getString(3), row.getString(4)));
    }

    public List<InvitationStatus> listInvitations() {
        return jdbc.query("""
                SELECT id,status,target_role,expires_at,created_by,claimed_by
                  FROM user_invitation ORDER BY created_at DESC
                """, (row, ignored) -> new InvitationStatus(row.getString(1), row.getString(2),
                row.getString(3), row.getObject(4, LocalDateTime.class),
                (Long) row.getObject(5), (Long) row.getObject(6)));
    }

    public void revokeInvitation(String id) {
        jdbc.update("UPDATE user_invitation SET status='REVOKED' WHERE id=? AND status='AVAILABLE'", id);
    }

    public void revokeUserSessions(long userId, LocalDateTime now) {
        jdbc.update("UPDATE user_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now, userId);
    }

    public record User(long id, String displayName, String status, String role) {
    }

    public record Invitation(String id, String role) {
    }
    public record InvitationStatus(String id, String status, String targetRole,
                                   LocalDateTime expiresAt, Long createdBy, Long claimedBy) {}
}
