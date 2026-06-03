package com.example.javaagentmvp.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final WechatAuthProperties properties;
    private final ObjectMapper objectMapper;

    public JwtService(WechatAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String generateToken(AuthenticatedUser user, Instant expiresAt) {
        try {
            long issuedAt = Instant.now().getEpochSecond();
            long exp = expiresAt.getEpochSecond();
            String headerJson = objectMapper.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", String.valueOf(user.userId()));
            payload.put("openid", user.openid());
            payload.put("role", user.role().value());
            payload.put("sid", user.sessionId());
            payload.put("jti", user.jti());
            payload.put("iat", issuedAt);
            payload.put("exp", exp);
            payload.put("iss", properties.jwtIssuer());
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headerEncoded = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadEncoded = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
            String content = headerEncoded + "." + payloadEncoded;
            return content + "." + sign(content);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to generate token", ex);
        }
    }

    public AuthenticatedUser verifyToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("invalid token format");
            }
            String content = parts[0] + "." + parts[1];
            if (!sign(content).equals(parts[2])) {
                throw new IllegalArgumentException("invalid token signature");
            }
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(payloadJson, MAP_TYPE);
            if (!properties.jwtIssuer().equals(String.valueOf(payload.get("iss")))) {
                throw new IllegalArgumentException("invalid token issuer");
            }
            long exp = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= exp) {
                throw new IllegalArgumentException("token expired");
            }
            long userId = Long.parseLong(String.valueOf(payload.get("sub")));
            String openid = String.valueOf(payload.get("openid"));
            UserRole role = UserRole.fromString(String.valueOf(payload.get("role")));
            String sessionId = String.valueOf(payload.get("sid"));
            String jti = String.valueOf(payload.get("jti"));
            return new AuthenticatedUser(userId, openid, role, sessionId, jti);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid token");
        }
    }

    private String sign(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(key);
        byte[] signature = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return base64Url(signature);
    }

    private String base64Url(byte[] content) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(content);
    }
}
