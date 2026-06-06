package com.example.javaagentmvp.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAccessPolicyTest {

    @Test
    void guestCanUseMiniProgramApisOnly() {
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/auth/me")).isTrue();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/conversations")).isTrue();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/conversations/abc/chat")).isTrue();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/admission/query")).isTrue();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/admin/users")).isFalse();
    }

    @Test
    void adminCanUseOperationalApis() {
        assertThat(ApiAccessPolicy.isAllowed(UserRole.ADMIN, "/api/conversations")).isTrue();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.ADMIN, "/api/admin/users")).isTrue();
    }

    @Test
    void loginPathIsPublic() {
        assertThat(ApiAccessPolicy.isPublicPath("/api/auth/wechat/login")).isTrue();
        assertThat(ApiAccessPolicy.isPublicPath("/api/auth/web/login")).isTrue();
    }
}
