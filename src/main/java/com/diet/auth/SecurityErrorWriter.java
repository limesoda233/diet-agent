package com.diet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

final class SecurityErrorWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SecurityErrorWriter() {}

    static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        JSON.writeValue(response.getWriter(), new ErrorBody(code, message,
                UUID.randomUUID().toString(), Instant.now().toString()));
    }

    private record ErrorBody(String code, String message, String traceId, String timestamp) {}
}
