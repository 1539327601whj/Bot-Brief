package com.ai.daily.service.impl;

import com.ai.daily.service.AiClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
public class AiClientServiceImpl implements AiClientService {

    /** 连接超时的上限。连不上就是连不上，不该陪着读超时一起等。 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    /** 低于这个值等于「必然超时」，夹到边界而不是照配。 */
    private static final int MIN_TIMEOUT_MS = 1_000;

    /** 高于这个值等于「没有超时」，也就失去了这一段的意义。 */
    private static final int MAX_TIMEOUT_MS = 300_000;

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
    public String chat(List<AiMessage> messages, double temperature, int maxTokens, int timeoutMs) {
        if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
            log.warn("AI 未配置，跳过调用 model={}", deepseekModel);
            return "AI 服务暂未配置，请先配置 DEEPSEEK_API_KEY。";
        }

        try (CloseableHttpClient client = httpClient(timeoutMs)) {
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

    /**
     * 带超时的 HTTP 客户端。
     *
     * <p>{@code HttpClients.createDefault()} 原本是**没有超时的**——读超时取 Apache 默认值，
     * 也就是无限。对话页上那意味着「一直转圈」，还能忍；代码查询页不行：
     * 那个端点前面已经跑了四路新闻外呼，最后再挂一段没有上限的等待，
     * 表现是整页卡住到浏览器自己超时，而页面上看不出是哪一段慢。
     *
     * <p>连接超时**单独限死 5 秒**：连不上就是连不上，没有理由陪着读超时一起等 25 秒。
     * 读超时用调用方给的值——它是「模型思考 + 生成」的预算，要和连接分开算。
     *
     * <p>传进来的值先夹到 [{@value #MIN_TIMEOUT_MS}, {@value #MAX_TIMEOUT_MS}]：
     * 配成 0 或负数会让 socket 立刻超时（比不配还糟），配成一天会让这段失去意义。
     * 夹逼而不报错，是因为这是个运维参数，写错时「按边界跑」比「整页 500」合适。
     */
    private CloseableHttpClient httpClient(int timeoutMs) {
        int read = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(Math.min(read, CONNECT_TIMEOUT_MS)))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.min(read, CONNECT_TIMEOUT_MS)))
                .setResponseTimeout(Timeout.ofMilliseconds(read))
                .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
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
