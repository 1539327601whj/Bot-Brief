package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 飞书群机器人推送（interactive 卡片格式）。
 * 支持"签名校验"：channel.secret 存放飞书后台的签名密钥。
 */
@Slf4j
@Service
public class FeishuChannelSender implements ChannelSender {

    private static final int TEXT_MAX = 30000;

    private final RestTemplate restTemplate;
    private final PushChannelValidator channelValidator;
    private final ProviderResponseValidator responseValidator;

    public FeishuChannelSender(@Qualifier("pushRestTemplate") RestTemplate restTemplate,
                               PushChannelValidator channelValidator,
                               ProviderResponseValidator responseValidator) {
        this.restTemplate = restTemplate;
        this.channelValidator = channelValidator;
        this.responseValidator = responseValidator;
    }

    @Override
    public String type() { return "feishu"; }

    @Override
    public void send(PushChannel channel, Report report) throws Exception {
        channelValidator.validateForSend(channel);
        Map<String, Object> body = new HashMap<>();
        long ts = System.currentTimeMillis() / 1000;
        if (channel.getSecret() != null && !channel.getSecret().isBlank()) {
            body.put("timestamp", String.valueOf(ts));
            body.put("sign", sign(ts, channel.getSecret()));
        }
        body.put("msg_type", "text");
        Map<String, Object> content = new HashMap<>();
        content.put("text", truncate(report.getTitle() + "\n\n" + report.getContent(), TEXT_MAX));
        body.put("content", content);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(channel.getTarget(), body, String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("飞书请求失败");
        }
        responseValidator.requireSuccess(type(), response);
        log.info("飞书推送成功 channel_id={} report_id={}", channel.getId(), report.getId());
    }

    private String sign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] data = mac.doFinal(new byte[]{});
        return Base64.getEncoder().encodeToString(data);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
