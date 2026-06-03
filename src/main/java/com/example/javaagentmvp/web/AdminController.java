package com.example.javaagentmvp.web;

import com.example.javaagentmvp.auth.AuthInterceptor;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.GuestQuotaService;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.auth.UserStatus;
import com.example.javaagentmvp.auth.persistence.mapper.WechatLoginAuditMapper;
import com.example.javaagentmvp.auth.persistence.mapper.WechatUserMapper;
import com.example.javaagentmvp.auth.persistence.model.WechatLoginAuditRow;
import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final WechatUserMapper wechatUserMapper;
    private final WechatLoginAuditMapper loginAuditMapper;
    private final GuestQuotaService guestQuotaService;

    public AdminController(
            WechatUserMapper wechatUserMapper,
            WechatLoginAuditMapper loginAuditMapper,
            GuestQuotaService guestQuotaService) {
        this.wechatUserMapper = wechatUserMapper;
        this.loginAuditMapper = loginAuditMapper;
        this.guestQuotaService = guestQuotaService;
    }

    @GetMapping("/users")
    public List<UserAdminDto> listUsers() {
        return wechatUserMapper.listAll().stream()
                .map(this::toAdminDto)
                .toList();
    }

    @PatchMapping("/users/{userId}")
    public UserAdminDto updateUser(@PathVariable long userId, @RequestBody UpdateUserRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        WechatUserRecord existing = wechatUserMapper.findById(userId);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        String nextRole = body.role() != null ? body.role().strip() : existing.role();
        String nextStatus = body.status() != null ? body.status().strip() : existing.status();
        validateRole(nextRole);
        validateStatus(nextStatus);
        wechatUserMapper.updateRoleAndStatus(userId, nextRole, nextStatus);
        if (Boolean.TRUE.equals(body.resetGuestQuota())) {
            guestQuotaService.resetGuestLimits(userId);
        }
        return toAdminDto(wechatUserMapper.findById(userId));
    }

    @GetMapping("/login-audits")
    public List<LoginAuditDto> listLoginAudits() {
        return loginAuditMapper.listRecent(200).stream()
                .map(row -> new LoginAuditDto(
                        row.id(),
                        row.openid(),
                        row.loginStatus(),
                        row.failureReason(),
                        row.ip(),
                        row.requestId(),
                        row.createdAt() != null ? row.createdAt().toString() : null))
                .toList();
    }

    private UserAdminDto toAdminDto(WechatUserRecord user) {
        var login = guestQuotaService.getLoginInfo(user.id(), UserRole.fromString(user.role()));
        return new UserAdminDto(
                user.id(),
                user.openid(),
                user.nickname(),
                user.avatarUrl(),
                user.role(),
                user.status(),
                login.used(),
                login.limit(),
                login.used(),
                login.limit(),
                user.updatedAt() != null ? user.updatedAt().toString() : null);
    }

    private void validateRole(String role) {
        try {
            UserRole.fromString(role);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid role");
        }
    }

    private void validateStatus(String status) {
        try {
            UserStatus.fromString(status);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status");
        }
    }

    public record UpdateUserRequest(String role, String status, Boolean resetGuestQuota) {
    }

    public record UserAdminDto(
            Long id,
            String openid,
            String nickname,
            String avatarUrl,
            String role,
            String status,
            int quotaUsed,
            int quotaLimit,
            int loginUsed,
            int loginLimit,
            String updatedAt) {
    }

    public record LoginAuditDto(
            Long id,
            String openid,
            String loginStatus,
            String failureReason,
            String ip,
            String requestId,
            String createdAt) {
    }
}
