package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WeChatChannelSenderTest {

    private static final String TARGET = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test_key";

    @Test
    void requiresBusinessSuccessAfterPostingPayload() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo(TARGET))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("{\"errcode\":40058,\"errmsg\":\"invalid key\"}", MediaType.APPLICATION_JSON));
        WeChatChannelSender sender = new WeChatChannelSender(restTemplate, new PushChannelValidator(),
                new ProviderResponseValidator(new ObjectMapper()));

        assertThatThrownBy(() -> sender.send(channel(), report()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("企业微信返回失败")
                .hasMessageNotContaining("invalid key");
        server.verify();
    }

    private PushChannel channel() {
        PushChannel channel = new PushChannel();
        channel.setId(1L);
        channel.setChannelType("wechat");
        channel.setTarget(TARGET);
        return channel;
    }

    private Report report() {
        Report report = new Report();
        report.setId(2L);
        report.setTitle("Daily");
        report.setContent("Content");
        return report;
    }
}
