package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.auth.persistence.mapper.WechatUserMapper;
import com.example.javaagentmvp.auth.persistence.model.WechatUserRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String AUTH_USER_ATTR = "AUTH_USER";

    private final JwtService jwtService;
    private final AuthSessionService authSessionService;
    private final WechatUserMapper wechatUserMapper;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(
            JwtService jwtService,
            AuthSessionService authSessionService,
            WechatUserMapper wechatUserMapper,
            ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.authSessionService = authSessionService;
        this.wechatUserMapper = wechatUserMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "missing token");
            return false;
        }
        try {
            AuthenticatedUser user = jwtService.verifyToken(authorization.substring("Bearer ".length()).trim());
            WechatUserRecord record = wechatUserMapper.findById(user.userId());
            if (record == null) {
                writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "user not found");
                return false;
            }
            if (UserStatus.DISABLED.value().equals(record.status())) {
                writeError(response, HttpStatus.FORBIDDEN, "USER_DISABLED", "账号已禁用");
                return false;
            }
            authSessionService.validateAndTouch(user.sessionId(), user.jti());
            String uri = request.getRequestURI();
            if (!ApiAccessPolicy.isAllowed(user.role(), uri)) {
                writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问该接口");
                return false;
            }
            request.setAttribute(AUTH_USER_ATTR, user);
            return true;
        } catch (AuthException ex) {
            writeError(response, ex.status(), ex.errorCode(), ex.getMessage());
            return false;
        } catch (IllegalArgumentException ex) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "invalid token");
            return false;
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error, String message) throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
