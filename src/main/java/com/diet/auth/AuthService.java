package com.diet.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final WechatCodeExchangeClient wechat;
    private final TokenCodec tokens;
    private final TransactionTemplate transactions;
    private final String appId;
    private final String bootstrapCode;
    private final Clock clock;
    private Duration sessionTtl = Duration.ofDays(7);

    @Autowired
    public AuthService(AuthRepository repository, WechatCodeExchangeClient wechat,
                       TransactionTemplate transactions,
                       @Value("${diet.wechat.app-id:wx15b95618032f7270}") String appId,
                       @Value("${diet.auth.bootstrap-invite-code:}") String bootstrapCode) {
        this(repository, wechat, new TokenCodec(), transactions,
                appId, bootstrapCode, Clock.systemUTC());
    }

    AuthService(AuthRepository repository, WechatCodeExchangeClient wechat,
                TokenCodec tokens, TransactionTemplate transactions,
                String appId, String bootstrapCode, Clock clock) {
        this.repository = repository;
        this.wechat = wechat;
        this.tokens = tokens;
        this.transactions = transactions;
        this.appId = appId;
        this.bootstrapCode = bootstrapCode;
        this.clock = clock;
    }

    @Value("${diet.auth.session-ttl:7d}")
    public void setSessionTtl(Duration sessionTtl) {
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("diet.auth.session-ttl must be positive");
        }
        this.sessionTtl = sessionTtl;
    }

    public LoginResponse login(String code, String invitationCode) {
        String openid = wechat.exchange(code);
        try {
            return transactions.execute(status -> loginWithinTransaction(openid, invitationCode));
        } catch (DuplicateKeyException race) {
            throw new AuthException("INVITATION_INVALID", "邀请码无效或已使用");
        }
    }

    private LoginResponse loginWithinTransaction(String openid, String invitationCode) {
        LocalDateTime now = now();
        AuthRepository.User user = repository.findIdentity(appId, openid);
        if (user == null) {
            if (invitationCode == null || invitationCode.isBlank()) {
                throw new AuthException("INVITATION_REQUIRED", "首次登录需要邀请码");
            }
            if (!bootstrapCode.isBlank()) {
                repository.ensureBootstrap(tokens.hash(bootstrapCode), now.plusDays(30), now);
            }
            AuthRepository.Invitation invitation = repository.findAvailableInvitation(
                    tokens.hash(invitationCode.trim()), now);
            if (invitation == null) {
                throw new AuthException("INVITATION_INVALID", "邀请码无效或已使用");
            }
            long userId = repository.createUser(invitation.role(), now);
            if (!repository.claimInvitation(invitation.id(), userId, now)) {
                throw new AuthException("INVITATION_INVALID", "邀请码无效或已使用");
            }
            repository.bindIdentity(userId, appId, openid, now);
            user = new AuthRepository.User(userId, null, "ACTIVE", invitation.role());
        } else if (!"ACTIVE".equals(user.status())) {
            throw new AuthException("ACCOUNT_DISABLED", "账号已停用");
        } else {
            repository.touchIdentity(appId, openid, now);
        }
        String token = tokens.newToken(32);
        LocalDateTime expiry = now.plus(sessionTtl);
        repository.createSession(UUID.randomUUID().toString(), user.id(), tokens.hash(token),
                expiry, now);
        return new LoginResponse(token, expiry.toInstant(ZoneOffset.UTC), user);
    }

    public AuthPrincipal authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new AuthException("UNAUTHENTICATED", "请先登录");
        }
        AuthPrincipal principal = repository.findSession(tokens.hash(token), now());
        if (principal == null) {
            throw new AuthException("SESSION_EXPIRED", "登录已过期");
        }
        return principal;
    }

    public void logout(String sessionId) {
        repository.revokeSession(sessionId, now());
    }

    public String createInvitation(long adminId) {
        String code = tokens.newToken(16);
        LocalDateTime now = now();
        repository.createInvitation(tokens.hash(code), adminId, now.plusDays(7), now);
        return code;
    }

    public void disableUser(long userId) {
        repository.disableUser(userId, now());
    }

    public void enableUser(long userId) { repository.enableUser(userId, now()); }
    public java.util.List<AuthRepository.User> listUsers() { return repository.listUsers(); }
    public java.util.List<AuthRepository.InvitationStatus> listInvitations() {
        return repository.listInvitations();
    }
    public void revokeInvitation(String id) { repository.revokeInvitation(id); }
    public void revokeUserSessions(long userId) { repository.revokeUserSessions(userId, now()); }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record LoginResponse(String token, Instant expiresAt, AuthRepository.User user) {
    }
}
