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
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/releases")).isFalse();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/db-provisioning")).isFalse();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.GUEST, "/api/admin/users")).isFalse();
    }

    @Test
    void adminCanUseOperationalApis() {
        assertThat(ApiAccessPolicy.isAllowed(UserRole.ADMIN, "/api/releases")).isTrue();
        assertThat(ApiAccessPolicy.isAllowed(UserRole.ADMIN, "/api/design-docs/content")).isTrue();
    }

    @Test
    void loginPathIsPublic() {
        assertThat(ApiAccessPolicy.isPublicPath("/api/auth/wechat/login")).isTrue();
    }
}
