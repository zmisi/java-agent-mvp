package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.auth.persistence.mapper.WechatLoginAuditMapper;
import com.example.javaagentmvp.auth.persistence.mapper.WechatUserMapper;
import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebAuthServiceTest {

    @Mock
    private WechatUserMapper wechatUserMapper;
    @Mock
    private WechatLoginAuditMapper loginAuditMapper;
    @Mock
    private WechatAuthService wechatAuthService;
    @Mock
    private HttpServletRequest httpRequest;

    private WebAuthService service;

    @BeforeEach
    void setUp() {
        WechatAuthProperties properties = new WechatAuthProperties(
                "mini-app",
                "mini-secret",
                "web-login-secret-with-32-chars-min",
                "web:console",
                "jwt-secret-with-at-least-32-characters",
                "wechat-mini-program",
                86400,
                3600,
                86400,
                10,
                10,
                30,
                "./data/wechat-avatars",
                "");
        service = new WebAuthService(wechatUserMapper, loginAuditMapper, wechatAuthService, properties);
    }

    @Test
    void loginIssuesAdminTokenForValidSecret() {
        WechatUserRecord user = new WechatUserRecord(
                9L,
                "web:console",
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
        when(wechatUserMapper.findByOpenid("web:console")).thenReturn(user);
        when(wechatUserMapper.findById(9L)).thenReturn(user);

        WechatAuthService.LoginResult expected = new WechatAuthService.LoginResult(
                "token",
                Instant.parse("2026-06-06T00:00:00Z").toString(),
                user,
                "req-1",
                GuestQuotaService.QuotaInfo.unlimited(),
                GuestQuotaService.QuotaInfo.unlimited());
        when(wechatAuthService.completeLogin(eq(user), eq("web:console"), eq(httpRequest), anyString()))
                .thenReturn(expected);

        WechatAuthService.LoginResult result =
                service.login("web-login-secret-with-32-chars-min", httpRequest);

        assertThat(result.token()).isEqualTo("token");
        verify(wechatUserMapper).upsert(any(WechatUserRecord.class));
        verify(wechatUserMapper).updateRoleAndStatus(9L, UserRole.ADMIN.value(), UserStatus.ACTIVE.value());
    }

    @Test
    void loginRejectsInvalidSecret() {
        assertThatThrownBy(() -> service.login("wrong-secret", httpRequest))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).status()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void loginDisabledWhenSecretNotConfigured() {
        WechatAuthProperties disabled = new WechatAuthProperties(
                "mini-app",
                "mini-secret",
                "",
                "web:console",
                "jwt-secret-with-at-least-32-characters",
                "wechat-mini-program",
                86400,
                3600,
                86400,
                10,
                10,
                30,
                "./data/wechat-avatars",
                "");
        WebAuthService disabledService =
                new WebAuthService(wechatUserMapper, loginAuditMapper, wechatAuthService, disabled);

        assertThat(disabledService.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabledService.login("any", httpRequest))
                .isInstanceOf(AuthException.class)
                .satisfies(ex -> assertThat(((AuthException) ex).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
