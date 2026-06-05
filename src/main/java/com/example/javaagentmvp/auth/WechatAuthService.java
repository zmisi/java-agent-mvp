package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.auth.persistence.mapper.WechatLoginAuditMapper;
import com.example.javaagentmvp.auth.persistence.mapper.WechatUserMapper;
import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class WechatAuthService {

    private final WechatCodeExchangeClient codeExchangeClient;
    private final WechatUserMapper wechatUserMapper;
    private final WechatLoginAuditMapper loginAuditMapper;
    private final JwtService jwtService;
    private final AuthSessionService authSessionService;
    private final GuestQuotaService guestQuotaService;
    private final WechatAuthProperties properties;

    public WechatAuthService(
            WechatCodeExchangeClient codeExchangeClient,
            WechatUserMapper wechatUserMapper,
            WechatLoginAuditMapper loginAuditMapper,
            JwtService jwtService,
            AuthSessionService authSessionService,
            GuestQuotaService guestQuotaService,
            WechatAuthProperties properties) {
        this.codeExchangeClient = codeExchangeClient;
        this.wechatUserMapper = wechatUserMapper;
        this.loginAuditMapper = loginAuditMapper;
        this.jwtService = jwtService;
        this.authSessionService = authSessionService;
        this.guestQuotaService = guestQuotaService;
        this.properties = properties;
    }

    public LoginResult login(LoginRequest request, HttpServletRequest httpRequest) {
        String requestId = UUID.randomUUID().toString();
        try {
            WechatCodeExchangeClient.WechatSessionResponse session = codeExchangeClient.exchangeCode(request.code());
            String role = resolveRoleForOpenid(session.openid());
            String nickname = defaultNickname(request.nickname());
            String avatarUrl = defaultAvatarUrl(request.avatarUrl());
            WechatUserRecord userToSave = new WechatUserRecord(
                    null,
                    session.openid(),
                    session.unionid(),
                    nickname,
                    avatarUrl,
                    request.province(),
                    request.city(),
                    request.gender(),
                    role,
                    UserStatus.ACTIVE.value(),
                    null,
                    null
            );
            wechatUserMapper.upsert(userToSave);
            WechatUserRecord user = wechatUserMapper.findByOpenid(session.openid());
            if (user == null || user.id() == null) {
                throw new IllegalStateException("用户信息保存失败");
            }
            if (isBootstrapAdmin(session.openid())) {
                wechatUserMapper.updateRoleAndStatus(user.id(), UserRole.ADMIN.value(), UserStatus.ACTIVE.value());
                user = wechatUserMapper.findById(user.id());
            }
            return completeLogin(user, session.openid(), httpRequest, requestId);
        } catch (Exception ex) {
            loginAuditMapper.insert(
                    null,
                    "FAILED",
                    ex.getMessage(),
                    clientIp(httpRequest),
                    httpRequest.getHeader("User-Agent"),
                    requestId);
            throw ex;
        }
    }

    public LoginResult completeLogin(
            WechatUserRecord user,
            String auditOpenid,
            HttpServletRequest httpRequest,
            String requestId) {
        guestQuotaService.ensureGuestUsage(user.id());
        UserRole userRole = UserRole.fromString(user.role());
        guestQuotaService.assertCanLogin(user.id(), userRole);

        AuthSessionService.SessionCreated sessionCreated = authSessionService.createSession(user.id());
        AuthenticatedUser authUser = new AuthenticatedUser(
                user.id(),
                user.openid(),
                userRole,
                sessionCreated.sessionId(),
                sessionCreated.jti());
        String token = jwtService.generateToken(authUser, sessionCreated.expiresAt());

        guestQuotaService.recordLogin(user.id(), userRole);

        loginAuditMapper.insert(
                auditOpenid,
                "SUCCESS",
                null,
                clientIp(httpRequest),
                httpRequest.getHeader("User-Agent"),
                requestId);

        GuestQuotaService.QuotaInfo quota = guestQuotaService.getQuotaInfo(user.id(), userRole);
        GuestQuotaService.QuotaInfo loginQuota = guestQuotaService.getLoginInfo(user.id(), userRole);
        return new LoginResult(
                token,
                sessionCreated.expiresAt().toString(),
                user,
                requestId,
                quota,
                loginQuota);
    }

    public UserProfile getCurrentUser(AuthenticatedUser authenticatedUser) {
        WechatUserRecord user = wechatUserMapper.findById(authenticatedUser.userId());
        if (user == null) {
            throw new AuthException("USER_NOT_FOUND", "用户不存在", HttpStatus.UNAUTHORIZED);
        }
        if (UserStatus.DISABLED.value().equals(user.status())) {
            throw new AuthException("USER_DISABLED", "账号已禁用", HttpStatus.FORBIDDEN);
        }
        UserRole role = UserRole.fromString(user.role());
        GuestQuotaService.QuotaInfo quota = guestQuotaService.getQuotaInfo(user.id(), role);
        GuestQuotaService.QuotaInfo loginQuota = guestQuotaService.getLoginInfo(user.id(), role);
        return new UserProfile(user, role, quota, loginQuota, properties.sessionIdleSeconds());
    }

    public void logout(AuthenticatedUser user) {
        authSessionService.revoke(user.sessionId());
    }

    public WechatUserRecord updateAvatarUrl(AuthenticatedUser user, String avatarUrl) {
        wechatUserMapper.updateAvatarUrl(user.userId(), avatarUrl);
        return requireUser(user.userId());
    }

    public WechatUserRecord updateNickname(AuthenticatedUser user, String nickname) {
        String value = defaultNickname(nickname);
        wechatUserMapper.updateNickname(user.userId(), value);
        return requireUser(user.userId());
    }

    private WechatUserRecord requireUser(long userId) {
        WechatUserRecord updated = wechatUserMapper.findById(userId);
        if (updated == null) {
            throw new AuthException("USER_NOT_FOUND", "用户不存在", HttpStatus.UNAUTHORIZED);
        }
        return updated;
    }

    private String resolveRoleForOpenid(String openid) {
        if (isBootstrapAdmin(openid)) {
            return UserRole.ADMIN.value();
        }
        WechatUserRecord existing = wechatUserMapper.findByOpenid(openid);
        if (existing != null && existing.role() != null && !existing.role().isBlank()) {
            return existing.role();
        }
        return UserRole.GUEST.value();
    }

    private boolean isBootstrapAdmin(String openid) {
        List<String> openids = properties.bootstrapAdminOpenidList();
        return openid != null && openids.stream().anyMatch(openid::equals);
    }

    public record LoginRequest(
            String code,
            String nickname,
            String avatarUrl,
            String province,
            String city,
            Integer gender) {
    }

    public record LoginResult(
            String token,
            String expiresAt,
            WechatUserRecord user,
            String requestId,
            GuestQuotaService.QuotaInfo quota,
            GuestQuotaService.QuotaInfo loginQuota) {
    }

    public record UserProfile(
            WechatUserRecord user,
            UserRole role,
            GuestQuotaService.QuotaInfo quota,
            GuestQuotaService.QuotaInfo loginQuota,
            long sessionIdleSeconds) {
    }

    private static final String DEFAULT_NICKNAME = "微信用户";
    private static final String DEFAULT_AVATAR_URL =
            "https://mmbiz.qpic.cn/mmbiz/icTdbqWNOwNRna42FI242Lcia07jQodd2FJGIYQfG0LAJGFxM4FbnQP6yfMxBgJ0F3YRqJCJ1aPAK2dQagdusBZg/0";

    private String defaultNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return DEFAULT_NICKNAME;
        }
        return nickname.strip();
    }

    private String defaultAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return DEFAULT_AVATAR_URL;
        }
        return avatarUrl.strip();
    }

    private String clientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
