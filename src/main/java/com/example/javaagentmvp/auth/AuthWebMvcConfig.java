package com.example.javaagentmvp.auth;

import com.example.javaagentmvp.chat.ui.UniversityProfileProperties;
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
    private final UniversityProfileProperties universityProfileProperties;

    public AuthWebMvcConfig(
            AuthInterceptor authInterceptor,
            WechatLoginRateLimitInterceptor loginRateLimitInterceptor,
            WechatAuthProperties wechatAuthProperties,
            UniversityProfileProperties universityProfileProperties) {
        this.authInterceptor = authInterceptor;
        this.loginRateLimitInterceptor = loginRateLimitInterceptor;
        this.wechatAuthProperties = wechatAuthProperties;
        this.universityProfileProperties = universityProfileProperties == null
                ? UniversityProfileProperties.defaults()
                : universityProfileProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRateLimitInterceptor)
                .addPathPatterns("/api/auth/wechat/login", "/api/auth/web/login");
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/wechat/login", "/api/auth/web/login", "/api/public/**");
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

        String logoLocation = Path.of(universityProfileProperties.logoDir())
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        if (!logoLocation.endsWith("/")) {
            logoLocation = logoLocation + "/";
        }
        registry.addResourceHandler("/university-logos/**")
                .addResourceLocations(logoLocation);
    }
}
