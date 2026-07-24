package com.ai.daily.service.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class ProviderResponseValidator {

    private final ObjectMapper objectMapper;

    public ProviderResponseValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void requireSuccess(String provider, ResponseEntity<String> response) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException(providerName(provider) + "请求失败");
        }
        requireSuccess(provider, response.getBody());
    }

    public void requireSuccess(String provider, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException(providerName(provider) + "返回空响应");
        }
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            boolean success = switch (provider) {
                case "wechat", "dingtalk" -> body.has("errcode") && body.get("errcode").canConvertToInt()
                        && body.get("errcode").intValue() == 0;
                case "feishu" -> zero(body.get("code")) || zero(body.get("StatusCode"));
                default -> false;
            };
            if (!success) throw new IllegalStateException(providerName(provider) + "返回失败");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(providerName(provider) + "返回无效响应");
        }
    }

    private boolean zero(JsonNode value) {
        return value != null && value.canConvertToInt() && value.intValue() == 0;
    }

    private String providerName(String provider) {
        return switch (provider) {
            case "wechat" -> "企业微信";
            case "dingtalk" -> "钉钉";
            case "feishu" -> "飞书";
            default -> "推送服务";
        };
    }
}
