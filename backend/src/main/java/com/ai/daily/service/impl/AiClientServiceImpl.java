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

@Service
public class AiClientServiceImpl implements AiClientService {

    @Value("${deepseek.api-key:}")
    private String deepseekApiKey;

    @Value("${deepseek.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String chat(String prompt) {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            return "AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。";
        }

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(deepseekBaseUrl + "/chat/completions");
            request.setHeader("Authorization", "Bearer " + deepseekApiKey);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(buildBody(prompt), StandardCharsets.UTF_8));

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

    private String buildBody(String prompt) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", deepseekModel);
        body.put("temperature", 0.7);
        body.put("max_tokens", 2048);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", prompt);
        return objectMapper.writeValueAsString(body);
    }
}
