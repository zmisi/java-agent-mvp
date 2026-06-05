package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.auth.persistence.mapper.WechatLoginAuditMapper;
import com.example.javaagentmvp.auth.persistence.mapper.WechatUserMapper;
import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@Service
public class WebAuthService {

    private final WechatUserMapper wechatUserMapper;
    private final WechatLoginAuditMapper loginAuditMapper;
    private final WechatAuthService wechatAuthService;
    private final WechatAuthProperties properties;

    public WebAuthService(
            WechatUserMapper wechatUserMapper,
            WechatLoginAuditMapper loginAuditMapper,
            WechatAuthService wechatAuthService,
            WechatAuthProperties properties) {
        this.wechatUserMapper = wechatUserMapper;
        this.loginAuditMapper = loginAuditMapper;
        this.wechatAuthService = wechatAuthService;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.webLoginEnabled();
    }

    public WechatAuthService.LoginResult login(String secret, HttpServletRequest httpRequest) {
        if (!isEnabled()) {
            throw new AuthException(
                    "WEB_LOGIN_DISABLED",
                    "Web console login is not configured",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        String requestId = UUID.randomUUID().toString();
        String openid = properties.resolvedWebLoginOpenid();
        try {
            if (!secretsMatch(secret, properties.webLoginSecret())) {
                throw new AuthException("UNAUTHORIZED", "invalid secret", HttpStatus.UNAUTHORIZED);
            }
            WechatUserRecord userToSave = new WechatUserRecord(
                    null,
                    openid,
                    null,
                    "Web Console",
                    null,
                    null,
                    null,
                    null,
                    UserRole.ADMIN.value(),
                    UserStatus.ACTIVE.value(),
                    null,
                    null);
            wechatUserMapper.upsert(userToSave);
            WechatUserRecord user = wechatUserMapper.findByOpenid(openid);
            if (user == null || user.id() == null) {
                throw new IllegalStateException("用户信息保存失败");
            }
            wechatUserMapper.updateRoleAndStatus(user.id(), UserRole.ADMIN.value(), UserStatus.ACTIVE.value());
            user = wechatUserMapper.findById(user.id());
            return wechatAuthService.completeLogin(user, openid, httpRequest, requestId);
        } catch (Exception ex) {
            loginAuditMapper.insert(
                    openid,
                    "FAILED",
                    ex.getMessage(),
                    clientIp(httpRequest),
                    httpRequest.getHeader("User-Agent"),
                    requestId);
            throw ex;
        }
    }

    private static boolean secretsMatch(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private String clientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
