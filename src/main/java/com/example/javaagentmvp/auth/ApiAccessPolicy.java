package com.example.javaagentmvp.auth;

/**
 * Path-based API authorization for the WeChat mini-program and the admin web UI.
 * <p>
 * Mini-program users ({@link UserRole#GUEST}, {@link UserRole#MEMBER}) may only call
 * auth, conversation, and admission endpoints. Operational endpoints (releases, provisioning,
 * design docs, RAG admin) require {@link UserRole#ADMIN}.
 */
public final class ApiAccessPolicy {

    private static final String MINI_PROGRAM_LOGIN_PATH = "/api/auth/wechat/login";
    private static final String WEB_LOGIN_PATH = "/api/auth/web/login";

    private ApiAccessPolicy() {
    }

    public static boolean isPublicPath(String uri) {
        if (uri == null) {
            return false;
        }
        String path = normalize(uri);
        return MINI_PROGRAM_LOGIN_PATH.equals(path) || WEB_LOGIN_PATH.equals(path);
    }

    public static boolean isAllowed(UserRole role, String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        String path = normalize(uri);
        if (isPublicPath(path)) {
            return true;
        }
        if (role == UserRole.ADMIN) {
            return path.startsWith("/api/");
        }
        return isMiniProgramPath(path);
    }

    private static boolean isMiniProgramPath(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/api/conversations/")
                || path.equals("/api/conversations")
                || path.startsWith("/api/admission/");
    }

    private static String normalize(String uri) {
        int query = uri.indexOf('?');
        String path = query >= 0 ? uri.substring(0, query) : uri;
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
