package com.example.javaagentmvp.auth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WechatCodeExchangeClient {

    private final WechatAuthProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public WechatCodeExchangeClient(
            WechatAuthProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public WechatSessionResponse exchangeCode(String code) {
        String url = "https://api.weixin.qq.com/sns/jscode2session"
                + "?appid=" + properties.appId()
                + "&secret=" + properties.appSecret()
                + "&js_code=" + code
                + "&grant_type=authorization_code";
        String rawResponse = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        WechatSessionResponse response;
        try {
            response = objectMapper.readValue(rawResponse, WechatSessionResponse.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("微信登录失败: 微信接口返回非 JSON 内容: " + rawResponse);
        }
        if (response == null) {
            throw new IllegalArgumentException("微信登录失败: 微信接口返回为空");
        }
        if (response.errorCode() != null && response.errorCode() != 0) {
            throw new IllegalArgumentException("微信登录失败: " + response.errorCode() + " " + response.errorMessage());
        }
        if (response.openid() == null || response.openid().isBlank()) {
            throw new IllegalArgumentException("微信登录失败: 未返回 openid");
        }
        return response;
    }

    public record WechatSessionResponse(
            String openid,
            String unionid,
            @JsonAlias("session_key") String sessionKey,
            @JsonAlias("errcode") Integer errorCode,
            @JsonAlias("errmsg") String errorMessage) {
    }
}
