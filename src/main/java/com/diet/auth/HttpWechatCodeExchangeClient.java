package com.diet.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class HttpWechatCodeExchangeClient implements WechatCodeExchangeClient {
    private static final Logger log = LoggerFactory.getLogger(HttpWechatCodeExchangeClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RestClient client;
    private final String appId;
    private final String appSecret;

    @Autowired
    public HttpWechatCodeExchangeClient(
            @Value("${diet.wechat.app-id:wx15b95618032f7270}") String appId,
            @Value("${diet.wechat.app-secret:}") String appSecret) {
        this(appId, appSecret, newClient());
    }

    HttpWechatCodeExchangeClient(String appId, String appSecret, RestClient client) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.client = client;
    }

    private static RestClient newClient() {
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(3000);
        transport.setReadTimeout(5000);
        return RestClient.builder().requestFactory(transport).build();
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
            String body = client.get().uri(url).retrieve().body(String.class);
            JsonNode response = JSON.readTree(body);
            if (response == null || response.path("errcode").asInt(0) != 0
                    || response.path("openid").asText("").isBlank()) {
                throw new AuthException("INVALID_WECHAT_CODE", "微信登录凭证无效");
            }
            return response.path("openid").asText();
        } catch (JsonProcessingException error) {
            log.warn("WeChat code exchange failed: invalid JSON response");
            throw new AuthException("WECHAT_UNAVAILABLE", "微信登录暂时不可用");
        } catch (AuthException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("WeChat code exchange failed: {}", failureCategory(error));
            throw new AuthException("WECHAT_UNAVAILABLE", "微信登录暂时不可用");
        }
    }

    private static String failureCategory(RuntimeException error) {
        if (error instanceof RestClientResponseException responseError) {
            return "HTTP " + responseError.getStatusCode().value();
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return error.getClass().getSimpleName() + " / " + root.getClass().getSimpleName();
    }
}
