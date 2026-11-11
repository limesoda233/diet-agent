package com.diet.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class HttpWechatCodeExchangeClient implements WechatCodeExchangeClient {
    private final RestClient client;
    private final String appId;
    private final String appSecret;

    public HttpWechatCodeExchangeClient(
            @Value("${diet.wechat.app-id:wx15b95618032f7270}") String appId,
            @Value("${diet.wechat.app-secret:}") String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(3000);
        transport.setReadTimeout(5000);
        this.client = RestClient.builder().requestFactory(transport).build();
    }

    @Override
    public String exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new AuthException("INVALID_WECHAT_CODE", "微信登录凭证无效");
        }
        if (appSecret.isBlank()) {
            throw new AuthException("WECHAT_NOT_CONFIGURED", "微信登录尚未配置");
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
                    .queryParam("appid", appId)
                    .queryParam("secret", appSecret)
                    .queryParam("js_code", code)
                    .queryParam("grant_type", "authorization_code")
                    .build().encode().toUriString();
            JsonNode response = client.get().uri(url).retrieve().body(JsonNode.class);
            if (response == null || response.path("errcode").asInt(0) != 0
                    || response.path("openid").asText("").isBlank()) {
                throw new AuthException("INVALID_WECHAT_CODE", "微信登录凭证无效");
            }
            return response.path("openid").asText();
        } catch (AuthException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AuthException("WECHAT_UNAVAILABLE", "微信登录暂时不可用");
        }
    }
}
