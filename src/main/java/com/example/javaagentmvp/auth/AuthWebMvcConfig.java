package com.example.javaagentmvp.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final WechatLoginRateLimitInterceptor loginRateLimitInterceptor;
    private final WechatAuthProperties wechatAuthProperties;

    public AuthWebMvcConfig(
            AuthInterceptor authInterceptor,
            WechatLoginRateLimitInterceptor loginRateLimitInterceptor,
            WechatAuthProperties wechatAuthProperties) {
        this.authInterceptor = authInterceptor;
        this.loginRateLimitInterceptor = loginRateLimitInterceptor;
        this.wechatAuthProperties = wechatAuthProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRateLimitInterceptor)
                .addPathPatterns("/api/auth/wechat/login", "/api/auth/web/login");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/wechat/login", "/api/auth/web/login");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(wechatAuthProperties.avatarUploadDir())
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/wechat-avatars/**")
                .addResourceLocations(location);
    }
}
