package com.example.javaagentmvp.auth;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public AuthException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }
}
