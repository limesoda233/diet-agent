package com.diet.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;
import java.time.Instant;
import com.diet.auth.AuthExceptionHandler.ApiError;

@RestControllerAdvice(basePackages = "com.diet.controller")
public class DietExceptionHandler {
    @ExceptionHandler(DuplicateMealException.class)
    public org.springframework.http.ResponseEntity<ApiError> duplicateMeal(DuplicateMealException error) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("DUPLICATE_MEAL",error.getMessage(),UUID.randomUUID().toString(),Instant.now()));
    }
    @ExceptionHandler(DietException.class)
    public org.springframework.http.ResponseEntity<ApiError> handleNewDietException(DietException e) {
        String code = e.getMessage() != null && e.getMessage().contains("不存在")
                ? "RESOURCE_NOT_FOUND" : "BAD_REQUEST";
        return org.springframework.http.ResponseEntity.status("RESOURCE_NOT_FOUND".equals(code)
                        ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST)
                .body(new ApiError(code, e.getMessage(), UUID.randomUUID().toString(), Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleException(Exception e) {
        return new ApiError("INTERNAL_ERROR", "服务异常", UUID.randomUUID().toString(), Instant.now());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleInvalidRequest(Exception ignored) {
        return new ApiError("BAD_REQUEST", "请求格式或参数无效", UUID.randomUUID().toString(), Instant.now());
    }
}
