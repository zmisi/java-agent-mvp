package com.example.javaagentmvp.auth.persistence.model;

import java.time.Instant;

public record WechatUserRecord(
        Long id,
        String openid,
        String unionid,
        String nickname,
        String avatarUrl,
        String province,
        String city,
        Integer gender,
        String role,
        String status,
        Instant createdAt,
        Instant updatedAt) {
}
