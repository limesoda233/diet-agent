package com.diet.controller.session;

import com.diet.auth.AuthPrincipal;
import com.diet.model.CreateSessionResponse;
import com.diet.service.session.SessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/diet/sessions")
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public CreateSessionResponse create(@AuthenticationPrincipal AuthPrincipal principal) {
        return new CreateSessionResponse(sessionService.createSession(principal.userId()));
    }
}
