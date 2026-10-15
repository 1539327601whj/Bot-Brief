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

    /**
     * 模型名。默认值与 {@code application.yml}、以及仓库里其余几处（两个 workflow、
     * {@code daily_report.py}、{@code docker-compose.yml}）保持一致，都是 {@code deepseek-v4-flash}。
     *
     * <p>这里原来写的是 {@code deepseek-v4-pro}——一个整套发布链路里别处都没用过的名字。
     * 名字不对的症状很难认：**HTTP 200、没有 error、choices 是空的**，
     * 返回的是一句「AI 暂未返回内容」，从这个现象完全推不到模型名上去。
     */
    @Value("${deepseek.model:deepseek-v4-flash}")
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
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    // 200、没有 error、却一个 choices 都没有：最常见的原因是模型名不对，
                    // 网关把请求接下来了但没给结果。**这一支原来和「正文是空的」共用一句话**，
                    // 而两者的处置完全不同（一个去改模型名，一个重试或放宽 max_tokens），
                    // 所以分开报，并把原文带出去。
                    String body = snippet(responseBody);
                    log.warn("AI 返回里没有 choices model={} httpStatus={} body={}",
                            deepseekModel, httpResponse.getCode(), body);
                    return "AI 返回里没有 choices（模型名 " + deepseekModel + " 可能不对）：" + body;
                }

                JsonNode message = choices.path(0).path("message");
                String content = message.path("content").asText();
                if (content == null || content.isBlank()) {
                    String finishReason = choices.path(0).path("finish_reason").asText("");
                    String reasoning = message.path("reasoning_content").asText("");
                    /*
                     * 正文是空的至少三种成因，处置各不相同，所以**这三样必须都记下来**：
                     * finish_reason（被截断就是 length）、有没有 reasoning_content
                     * （思考型模型会把预算先花在思考上，正文一个字不剩）、以及原文。
                     *
                     * 原来这一行只记了 httpStatus 与消息条数。线上真的踩到时，
                     * 日志里只有「返回空内容 httpStatus=200」，等于什么都没说——
                     * 200 是必然的（否则走不到这支），消息条数也不可能是别的原因。
                     */
                    String body = snippet(responseBody);
                    log.warn("AI 返回空正文 model={} httpStatus={} messages={} finishReason={} "
                                    + "reasoningChars={} body={}",
                            deepseekModel, httpResponse.getCode(), messages.size(),
                            finishReason.isBlank() ? "(没给)" : finishReason,
                            reasoning.length(), body);
                    if ("length".equals(finishReason)) {
                        return "AI 输出被 max_tokens 截断了，正文没留下（本次上限 " + maxTokens
                                + "），把这个上限调大后重试。";
                    }
                    if (!reasoning.isBlank()) {
                        return "AI 只返回了思考过程、没有正文（推理占满了 max_tokens " + maxTokens
                                + " 的预算），把上限调大后重试。";
                    }
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
     * 一段响应体，压成一行、截短，够塞进日志。
     *
     * <p><b>「返回空正文」这一类失败，不留下原文就等于没法查。</b> HTTP 200、没有 error、
     * content 是空的——光凭这三个字排不出是模型名不对、被截断、还是有别的策略，
     * 而原文里 {@code finish_reason} 与 {@code reasoning_content} 一读就明白。
     *
     * <p>截到 800 字符：够看清结构，又不会把整段材料复述回日志。换行压成空格，
     * 否则一条日志会被拆成几十行，在日志文件里根本连不起来。
     */
    private static String snippet(String body) {
        if (body == null) {
            return "(空)";
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 800 ? flat : flat.substring(0, 800) + "…";
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
