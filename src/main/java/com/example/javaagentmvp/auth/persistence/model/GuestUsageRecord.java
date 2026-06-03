package com.example.javaagentmvp.auth.persistence.model;

import java.time.Instant;

public record GuestUsageRecord(
        Long userId,
        int usedCount,
        int quotaLimit,
        int loginCount,
        int loginLimit,
        Instant updatedAt) {
}
