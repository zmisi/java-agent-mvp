package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.auth.persistence.mapper.GuestUsageMapper;
import com.example.javaagentmvp.auth.persistence.model.GuestUsageRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GuestQuotaService {

    private final GuestUsageMapper guestUsageMapper;
    private final WechatAuthProperties properties;

    public GuestQuotaService(GuestUsageMapper guestUsageMapper, WechatAuthProperties properties) {
        this.guestUsageMapper = guestUsageMapper;
        this.properties = properties;
    }

    public void ensureGuestUsage(long userId) {
        int limit = properties.guestLoginLimit();
        guestUsageMapper.insertIfMissing(userId, limit, limit);
    }

    /** Guest usage limit is based on login count only. */
    public QuotaInfo getQuotaInfo(long userId, UserRole role) {
        return getLoginInfo(userId, role);
    }

    public QuotaInfo getLoginInfo(long userId, UserRole role) {
        if (role != UserRole.GUEST) {
            return QuotaInfo.unlimited();
        }
        ensureGuestUsage(userId);
        GuestUsageRecord usage = guestUsageMapper.findByUserId(userId);
        if (usage == null) {
            int limit = properties.guestLoginLimit();
            return new QuotaInfo(limit, 0, limit);
        }
        int remaining = Math.max(usage.loginLimit() - usage.loginCount(), 0);
        return new QuotaInfo(usage.loginLimit(), usage.loginCount(), remaining);
    }

    public void assertCanLogin(long userId, UserRole role) {
        if (role != UserRole.GUEST) {
            return;
        }
        QuotaInfo login = getLoginInfo(userId, role);
        if (login.remaining() <= 0) {
            throw new AuthException(
                    "LOGIN_LIMIT_EXCEEDED",
                    "登录次数已达上限，请升级为会员后继续使用",
                    HttpStatus.FORBIDDEN);
        }
    }

    public void recordLogin(long userId, UserRole role) {
        if (role != UserRole.GUEST) {
            return;
        }
        guestUsageMapper.incrementLoginCount(userId);
        guestUsageMapper.insertEvent(userId, "login");
    }

    public void resetGuestLimits(long userId) {
        ensureGuestUsage(userId);
        guestUsageMapper.resetGuestLimits(userId);
    }

    public record QuotaInfo(int limit, int used, int remaining) {
        public static QuotaInfo unlimited() {
            return new QuotaInfo(-1, 0, -1);
        }
    }
}
