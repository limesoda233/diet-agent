package com.diet.auth;

public class AuthException extends RuntimeException {
    private final String code;

    public AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
