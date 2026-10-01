package com.ai.daily.service.impl;

import com.ai.daily.service.AiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
public class AiClientServiceImpl implements AiClientService {

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.model:deepseek-v4-pro}")
    private String deepseekModel;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String chat(String prompt) {
        return chat(List.of(new AiMessage("user", prompt == null ? "" : prompt)), 0.7, 2048);
    }

    @Override
    public String chat(List<AiMessage> messages, double temperature, int maxTokens) {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            log.warn("AI 未配置，跳过调用 model={}", deepseekModel);
            return "AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。";
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(deepseekBaseUrl + "/chat/completions");
            request.setHeader("Authorization", "Bearer " + deepseekApiKey);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(buildBody(messages, temperature, maxTokens), StandardCharsets.UTF_8));

            try (CloseableHttpResponse httpResponse = client.execute(request)) {
                String responseBody = EntityUtils.toString(httpResponse.getEntity());
                JsonNode root = objectMapper.readTree(responseBody);
                if (root.has("error")) {
                    String errorMessage = root.path("error").path("message").asText();
                    log.warn("AI 返回错误 model={} error={}", deepseekModel, errorMessage);
                    return "AI 调用失败：" + errorMessage;
                }
                String content = root.path("choices").path(0).path("message").path("content").asText();
                if (content == null || content.isBlank()) {
                    log.warn("AI 返回空内容 model={} httpStatus={} messages={}",
                            deepseekModel, httpResponse.getCode(), messages.size());
                    return "AI 暂未返回内容，请稍后重试。";
                }
                return content;
            }
        } catch (Exception e) {
            // 调用方拿到的仍是一句文案，这里必须留下堆栈，否则线上完全查不到原因
            log.error("AI 调用失败 model={} baseUrl={} messages={}", deepseekModel, deepseekBaseUrl, messages.size(), e);
            return "AI 调用失败：" + e.getMessage();
        }
    }

    private String buildBody(List<AiMessage> messages, double temperature, int maxTokens) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", deepseekModel);
        body.put("temperature", temperature);
        body.put("max_tokens", Math.max(64, maxTokens));
        ArrayNode array = body.putArray("messages");
        if (messages != null) {
            for (AiMessage item : messages) {
                if (item == null || item.content() == null || item.content().isBlank()) continue;
                String role = switch (item.role() == null ? "" : item.role()) {
                    case "system", "assistant" -> item.role();
                    default -> "user";
                };
                ObjectNode message = array.addObject();
                message.put("role", role);
                message.put("content", item.content());
            }
        }
        return objectMapper.writeValueAsString(body);
    }
}
