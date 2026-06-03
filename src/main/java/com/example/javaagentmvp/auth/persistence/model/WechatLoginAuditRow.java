package com.example.javaagentmvp.auth.persistence.model;

import java.time.Instant;

public record WechatLoginAuditRow(
        Long id,
        String openid,
        String loginStatus,
        String failureReason,
        String ip,
        String userAgent,
        String requestId,
        Instant createdAt) {
}
