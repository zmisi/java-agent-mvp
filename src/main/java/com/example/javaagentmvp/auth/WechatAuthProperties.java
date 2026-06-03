package com.example.javaagentmvp.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "app.wechat")
public record WechatAuthProperties(
        String appId,
        String appSecret,
        String jwtSecret,
        String jwtIssuer,
        long tokenTtlSeconds,
        long sessionIdleSeconds,
        long sessionMaxSeconds,
        int guestQuotaLimit,
        int guestLoginLimit,
        String avatarUploadDir,
        String bootstrapAdminOpenids) {

    public List<String> bootstrapAdminOpenidList() {
        if (bootstrapAdminOpenids == null || bootstrapAdminOpenids.isBlank()) {
            return List.of();
        }
        return Arrays.stream(bootstrapAdminOpenids.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
