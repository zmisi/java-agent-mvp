package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.auth.persistence.mapper.AuthSessionMapper;
import com.example.javaagentmvp.auth.persistence.model.AuthSessionRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthSessionService {

    private final AuthSessionMapper authSessionMapper;
    private final WechatAuthProperties properties;

    public AuthSessionService(AuthSessionMapper authSessionMapper, WechatAuthProperties properties) {
        this.authSessionMapper = authSessionMapper;
        this.properties = properties;
    }

    public SessionCreated createSession(long userId) {
        Instant now = Instant.now();
        long maxSeconds = Math.max(properties.sessionMaxSeconds(), 300);
        Instant expiresAt = now.plusSeconds(maxSeconds);
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String jti = UUID.randomUUID().toString().replace("-", "");
        authSessionMapper.insert(sessionId, userId, jti, now, now, expiresAt);
        return new SessionCreated(sessionId, jti, expiresAt);
    }

    public void validateAndTouch(String sessionId) {
        AuthSessionRecord session = authSessionMapper.findById(sessionId);
        if (session == null || session.revokedAt() != null) {
            throw new AuthException("SESSION_REVOKED", "会话已失效，请重新登录", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        Instant now = Instant.now();
        if (now.isAfter(session.expiresAt())) {
            throw new AuthException("SESSION_EXPIRED", "会话已过期，请重新登录", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        long idleSeconds = Math.max(properties.sessionIdleSeconds(), 60);
        if (now.isAfter(session.lastActiveAt().plusSeconds(idleSeconds))) {
            throw new AuthException("SESSION_IDLE_TIMEOUT", "超过1小时未操作，请重新登录", org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        authSessionMapper.touchLastActive(sessionId, now);
    }

    public void revoke(String sessionId) {
        authSessionMapper.revoke(sessionId, Instant.now());
    }

    public record SessionCreated(String sessionId, String jti, Instant expiresAt) {
    }
}
