package com.diet.controller.auth;

import com.diet.auth.AuthPrincipal;
import com.diet.auth.AuthService;
import com.diet.auth.LoginRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final LoginRateLimiter limiter;

    public AuthController(AuthService auth, LoginRateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping("/wechat/login")
    public AuthService.LoginResponse login(@RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        limiter.check(servletRequest.getRemoteAddr());
        return auth.login(request.code(), request.invitationCode());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthPrincipal principal) {
        auth.logout(principal.sessionId());
    }

    public record LoginRequest(String code, String invitationCode) {
    }
}
