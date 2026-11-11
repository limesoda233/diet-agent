package com.diet.controller.diagnostics;

import com.diet.auth.AuthPrincipal;
import com.diet.auth.DiagnosticConsentService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/diagnostics/consents")
public class DiagnosticController {
    private final DiagnosticConsentService service;
    public DiagnosticController(DiagnosticConsentService service) { this.service = service; }

    @PostMapping
    public DiagnosticConsentService.Consent create(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.create(principal.userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        service.revoke(principal.userId(), id);
    }
}
