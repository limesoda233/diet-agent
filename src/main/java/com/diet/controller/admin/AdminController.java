package com.diet.controller.admin;

import com.diet.auth.AuthPrincipal;
import com.diet.auth.AuthService;
import com.diet.auth.AuthRepository;
import com.diet.auth.AccountService;
import com.diet.auth.AuthException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AuthService auth;
    private final AccountService accounts;

    public AdminController(AuthService auth, AccountService accounts) {
        this.auth = auth;
        this.accounts = accounts;
    }

    @GetMapping("/invitations")
    public java.util.List<AuthRepository.InvitationStatus> invitations() { return auth.listInvitations(); }

    @DeleteMapping("/invitations/{id}")
    public void revokeInvitation(@PathVariable String id) { auth.revokeInvitation(id); }

    @GetMapping("/users")
    public java.util.List<AuthRepository.User> users() { return auth.listUsers(); }

    @DeleteMapping("/users/{userId}/sessions")
    public void revokeSessions(@PathVariable long userId) { auth.revokeUserSessions(userId); }

    @PostMapping("/invitations")
    public InvitationResponse createInvitation(@AuthenticationPrincipal AuthPrincipal principal) {
        return new InvitationResponse(auth.createInvitation(principal.userId()));
    }

    @PutMapping("/users/{userId}/status")
    public void disable(@PathVariable long userId, @RequestBody StatusRequest request) {
        if ("ACTIVE".equals(request.status())) {
            auth.enableUser(userId);
        } else if ("DISABLED".equals(request.status())) {
            if ("ADMIN".equals(accounts.profile(userId).role()) && accounts.activeAdminCount() <= 1) {
                throw new AuthException("LAST_ADMIN", "请先创建另一位管理员");
            }
            auth.disableUser(userId);
        } else {
            throw new AuthException("INVALID_STATUS", "账号状态无效");
        }
    }

    public record InvitationResponse(String invitationCode) {
    }

    public record StatusRequest(String status) {
    }
}
