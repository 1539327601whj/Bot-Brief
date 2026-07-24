package com.ai.daily.service.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderResponseValidatorTest {

    private final ProviderResponseValidator validator = new ProviderResponseValidator(new ObjectMapper());

    @Test
    void acceptsProviderBusinessSuccessResponses() {
        assertThatCode(() -> validator.requireSuccess("wechat", ok("{\"errcode\":0}"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireSuccess("dingtalk", ok("{\"errcode\":0}"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireSuccess("feishu", ok("{\"code\":0}"))).doesNotThrowAnyException();
        assertThatCode(() -> validator.requireSuccess("feishu", ok("{\"StatusCode\":0}"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTransportEmptyMalformedAndBusinessFailuresWithSanitizedMessages() {
        assertThatThrownBy(() -> validator.requireSuccess("wechat", new ResponseEntity<>("{}", HttpStatus.BAD_GATEWAY)))
                .hasMessage("企业微信请求失败");
        assertThatThrownBy(() -> validator.requireSuccess("dingtalk", ok(null)))
                .hasMessage("钉钉返回空响应");
        assertThatThrownBy(() -> validator.requireSuccess("feishu", ok("not-json")))
                .hasMessage("飞书返回无效响应");
        assertThatThrownBy(() -> validator.requireSuccess("wechat", ok("{\"errcode\":40058,\"errmsg\":\"secret target\"}")))
                .hasMessage("企业微信返回失败")
                .hasMessageNotContaining("secret target");
    }

    private ResponseEntity<String> ok(String body) {
        return new ResponseEntity<>(body, HttpStatus.OK);
    }
}
