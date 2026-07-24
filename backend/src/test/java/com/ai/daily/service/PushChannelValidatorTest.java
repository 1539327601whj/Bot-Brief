package com.ai.daily.service;

import com.ai.daily.entity.PushChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PushChannelValidatorTest {

    private final PushChannelValidator validator = new PushChannelValidator();

    @Test
    void acceptsSupportedOfficialTargets() {
        assertThatCode(() -> validator.validate("email", "user@example.com")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("wechat", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc_123"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("dingtalk", "https://oapi.dingtalk.com/robot/send?access_token=abc-123"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate("feishu", "https://open.feishu.cn/open-apis/bot/v2/hook/abc_123"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeWebhookVariantsAndMultipleRecipients() {
        assertThatThrownBy(() -> validator.validate("wechat", "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("dingtalk", "https://evil.example/robot/send?access_token=abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("feishu", "https://open.feishu.cn/open-apis/bot/v2/hook/abc?token=secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("email", "one@example.com,two@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate("wechat", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + "a".repeat(1500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Webhook 地址过长");
        assertThatThrownBy(() -> validator.validateSecret("s".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("签名密钥过长");
    }

    @Test
    void revalidatesChannelTypeAndTargetBeforeSend() {
        PushChannel channel = new PushChannel();
        channel.setChannelType("wechat");
        channel.setTarget("https://evil.example/hook");

        assertThatThrownBy(() -> validator.validateForSend(channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Webhook 地址不是受支持的官方机器人地址");
    }
}
