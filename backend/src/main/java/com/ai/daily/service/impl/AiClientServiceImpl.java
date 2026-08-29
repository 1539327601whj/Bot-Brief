package com.ai.daily.service.impl;

import com.ai.daily.service.AiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                    return "AI 调用失败：" + root.path("error").path("message").asText();
                }
                String content = root.path("choices").path(0).path("message").path("content").asText();
                return content == null || content.isBlank() ? "AI 暂未返回内容，请稍后重试。" : content;
            }
        } catch (Exception e) {
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
