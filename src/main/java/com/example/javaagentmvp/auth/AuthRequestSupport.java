package com.example.javaagentmvp.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthRequestSupport {

    private AuthRequestSupport() {
    }

    public static AuthenticatedUser requireUser(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthInterceptor.AUTH_USER_ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        return user;
    }
}
