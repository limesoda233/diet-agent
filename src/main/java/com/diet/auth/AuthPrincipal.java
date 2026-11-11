package com.diet.auth;

public record AuthPrincipal(long userId, String role, String sessionId) {
}
