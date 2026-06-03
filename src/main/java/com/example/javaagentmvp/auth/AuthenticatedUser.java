package com.example.javaagentmvp.auth;

public record AuthenticatedUser(
        long userId,
        String openid,
        UserRole role,
        String sessionId,
        String jti) {
}
