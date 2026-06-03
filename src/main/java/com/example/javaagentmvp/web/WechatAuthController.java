package com.example.javaagentmvp.web;

import com.example.javaagentmvp.auth.AuthInterceptor;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.GuestQuotaService;
import com.example.javaagentmvp.auth.WechatAuthService;
import com.example.javaagentmvp.auth.WechatAvatarStorage;
import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Base64;

@RestController
@RequestMapping("/api/auth")
public class WechatAuthController {

    private final WechatAuthService authService;
    private final WechatAvatarStorage avatarStorage;

    public WechatAuthController(WechatAuthService authService, WechatAvatarStorage avatarStorage) {
        this.authService = authService;
        this.avatarStorage = avatarStorage;
    }

    @PostMapping("/wechat/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "code is required");
        }
        WechatAuthService.LoginResult result = authService.login(
                new WechatAuthService.LoginRequest(
                        request.code(),
                        request.nickname(),
                        request.avatarUrl(),
                        request.province(),
                        request.city(),
                        request.gender()),
                httpRequest
        );
        return new LoginResponse(
                result.token(),
                result.expiresAt(),
                toUserDto(result.user()),
                toQuotaDto(result.quota()),
                toQuotaDto(result.loginQuota()),
                result.requestId()
        );
    }

    @GetMapping("/me")
    public MeResponse me(HttpServletRequest request) {
        AuthenticatedUser authUser = requireUser(request);
        WechatAuthService.UserProfile profile = authService.getCurrentUser(authUser);
        return new MeResponse(
                toUserDto(profile.user()),
                profile.role().value(),
                toQuotaDto(profile.quota()),
                toQuotaDto(profile.loginQuota()),
                profile.sessionIdleSeconds()
        );
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        authService.logout(requireUser(request));
    }

    @PatchMapping("/profile")
    public UserDto updateProfile(@RequestBody UpdateProfileRequest body, HttpServletRequest request) {
        if (body == null || body.nickname() == null || body.nickname().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nickname is required");
        }
        AuthenticatedUser authUser = requireUser(request);
        WechatUserRecord user = authService.updateNickname(authUser, body.nickname());
        return toUserDto(user);
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AvatarUploadResponse uploadAvatar(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        AuthenticatedUser authUser = requireUser(request);
        return saveAvatar(authUser, avatarStorage.store(authUser.userId(), file), request);
    }

    @PostMapping("/avatar/base64")
    public AvatarUploadResponse uploadAvatarBase64(
            @RequestBody AvatarBase64Request body,
            HttpServletRequest request) {
        if (body == null || body.dataBase64() == null || body.dataBase64().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataBase64 is required");
        }
        AuthenticatedUser authUser = requireUser(request);
        byte[] bytes = decodeBase64(body.dataBase64());
        WechatAvatarStorage.StoredAvatar stored = avatarStorage.storeBytes(
                authUser.userId(),
                bytes,
                body.contentType());
        return saveAvatar(authUser, stored, request);
    }

    private AvatarUploadResponse saveAvatar(
            AuthenticatedUser authUser,
            WechatAvatarStorage.StoredAvatar stored,
            HttpServletRequest request) {
        String avatarUrl = ServletUriComponentsBuilder.fromContextPath(request)
                .path(stored.publicPath())
                .toUriString();
        WechatUserRecord user = authService.updateAvatarUrl(authUser, avatarUrl);
        return new AvatarUploadResponse(avatarUrl, toUserDto(user));
    }

    private static byte[] decodeBase64(String raw) {
        String value = raw.strip();
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma > 0) {
            value = value.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid base64 avatar data");
        }
    }

    private AuthenticatedUser requireUser(HttpServletRequest request) {
        AuthenticatedUser authUser = (AuthenticatedUser) request.getAttribute(AuthInterceptor.AUTH_USER_ATTR);
        if (authUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        return authUser;
    }

    private UserDto toUserDto(WechatUserRecord user) {
        return new UserDto(
                user.id(),
                user.openid(),
                user.nickname(),
                user.avatarUrl(),
                user.role(),
                user.status());
    }

    private QuotaDto toQuotaDto(GuestQuotaService.QuotaInfo quota) {
        return new QuotaDto(quota.limit(), quota.used(), quota.remaining());
    }

    public record LoginRequest(
            String code,
            String nickname,
            String avatarUrl,
            String province,
            String city,
            Integer gender) {
    }

    public record UserDto(
            Long id,
            String openid,
            String nickname,
            String avatarUrl,
            String role,
            String status) {
    }

    public record QuotaDto(int limit, int used, int remaining) {
    }

    public record LoginResponse(
            String token,
            String expiresAt,
            UserDto user,
            QuotaDto quota,
            QuotaDto loginQuota,
            String requestId) {
    }

    public record MeResponse(
            UserDto user,
            String role,
            QuotaDto quota,
            QuotaDto loginQuota,
            long sessionIdleSeconds) {
    }

    public record AvatarUploadResponse(String avatarUrl, UserDto user) {
    }

    public record AvatarBase64Request(String contentType, String dataBase64) {
    }

    public record UpdateProfileRequest(String nickname) {
    }
}
