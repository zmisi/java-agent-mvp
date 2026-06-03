package com.example.javaagentmvp.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.javaagentmvp.auth.AuthException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class RestApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestApiExceptionHandler.class);

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Request failed: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "provisioning_error");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException ex) {
        log.warn("Auth failed: {} {}", ex.errorCode(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.errorCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex) {
        Throwable root = ex.getMostSpecificCause();
        log.error("Database error: {}", root.getMessage(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "database_error");
        body.put("message", root.getMessage() != null ? root.getMessage() : ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
