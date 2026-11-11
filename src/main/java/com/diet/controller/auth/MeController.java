package com.diet.controller.auth;

import com.diet.auth.AccountService;
import com.diet.auth.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final AccountService accounts;
    public MeController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping
    public AccountService.UserProfile get(@AuthenticationPrincipal AuthPrincipal principal) {
        return accounts.profile(principal.userId());
    }

    @PatchMapping
    public AccountService.UserProfile rename(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody RenameRequest request) {
        return accounts.rename(principal.userId(), request.displayName());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal) {
        accounts.delete(principal.userId());
    }

    public record RenameRequest(String displayName) {}
}
