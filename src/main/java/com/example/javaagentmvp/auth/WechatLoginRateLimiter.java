package com.example.javaagentmvp.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window rate limiter for {@code /api/auth/wechat/login}.
 */
@Component
public class WechatLoginRateLimiter {

    private final WechatAuthProperties properties;
    private final Map<String, Deque<Long>> attemptsByKey = new ConcurrentHashMap<>();

    public WechatLoginRateLimiter(WechatAuthProperties properties) {
        this.properties = properties;
    }

    public void check(String clientKey) {
        int limit = Math.max(properties.loginRateLimitPerMinute(), 1);
        long windowStart = Instant.now().getEpochSecond() - 60;
        Deque<Long> window = attemptsByKey.computeIfAbsent(clientKey, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < windowStart) {
                window.removeFirst();
            }
            if (window.size() >= limit) {
                throw new AuthException(
                        "LOGIN_RATE_LIMITED",
                        "登录过于频繁，请稍后再试",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            window.addLast(Instant.now().getEpochSecond());
        }
    }
}
