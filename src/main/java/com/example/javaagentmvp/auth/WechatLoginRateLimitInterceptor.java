package com.example.javaagentmvp.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WechatLoginRateLimitInterceptor implements HandlerInterceptor {

    private final WechatLoginRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public WechatLoginRateLimitInterceptor(WechatLoginRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        try {
            rateLimiter.check(clientKey(request));
            return true;
        } catch (AuthException ex) {
            response.setStatus(ex.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", ex.errorCode());
            body.put("message", ex.getMessage());
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return false;
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
