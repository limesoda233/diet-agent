package com.diet.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiError> handle(AuthException error) {
        HttpStatus status = switch (error.code()) {
            case "UNAUTHENTICATED", "SESSION_EXPIRED" -> HttpStatus.UNAUTHORIZED;
            case "ACCOUNT_DISABLED" -> HttpStatus.FORBIDDEN;
            case "INVITATION_INVALID", "LAST_ADMIN" -> HttpStatus.CONFLICT;
            case "RESOURCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "WECHAT_UNAVAILABLE", "WECHAT_NOT_CONFIGURED" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(new ApiError(error.code(), error.getMessage(), UUID.randomUUID().toString(), Instant.now()));
    }

    public record ApiError(String code, String message, String traceId, Instant timestamp) {
    }
}
