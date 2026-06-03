package com.example.javaagentmvp.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WechatAuthSecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(WechatAuthSecurityValidator.class);
    private static final String DEFAULT_JWT_SECRET = "replace-this-with-env-secret";

    private final WechatAuthProperties properties;

    public WechatAuthSecurityValidator(WechatAuthProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        if (properties.appId() == null || properties.appId().isBlank()) {
            log.warn("WECHAT_APP_ID is not set; WeChat mini-program login is disabled");
            return;
        }
        if (properties.appSecret() == null || properties.appSecret().isBlank()) {
            throw new IllegalStateException("WECHAT_APP_SECRET is required when WECHAT_APP_ID is set");
        }
        String secret = properties.jwtSecret();
        if (secret == null
                || secret.isBlank()
                || DEFAULT_JWT_SECRET.equals(secret)
                || secret.length() < 32) {
            throw new IllegalStateException(
                    "WECHAT_JWT_SECRET must be a random string of at least 32 characters (not the default placeholder)");
        }
        log.info("WeChat auth security checks passed");
    }
}
