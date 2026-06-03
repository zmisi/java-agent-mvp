package com.example.javaagentmvp.auth.persistence.model;

import java.time.Instant;

public record AuthSessionRecord(
        String id,
        Long userId,
        String jwtJti,
        Instant issuedAt,
        Instant lastActiveAt,
        Instant expiresAt,
        Instant revokedAt) {
}
