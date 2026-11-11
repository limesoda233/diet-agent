package com.diet.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpWechatCodeExchangeClientTest {
    @Test
    void acceptsJsonWithAnUnexpectedContentType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.weixin.qq.com/sns/jscode2session"
                        + "?appid=app-id&secret=app-secret&js_code=login-code&grant_type=authorization_code"))
                .andRespond(withSuccess("{\"openid\":\"openid-1\",\"session_key\":\"session-1\"}",
                        MediaType.TEXT_PLAIN));

        HttpWechatCodeExchangeClient exchange =
                new HttpWechatCodeExchangeClient("app-id", "app-secret", builder.build());

        assertThat(exchange.exchange("login-code")).isEqualTo("openid-1");
        server.verify();
    }
}
