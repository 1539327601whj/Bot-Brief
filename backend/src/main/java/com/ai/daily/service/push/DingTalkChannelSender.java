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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉群机器人 markdown 推送。
 * 支持"加签"安全设置：channel.secret 存放钉钉后台的签名密钥。
 */
@Slf4j
@Service
public class DingTalkChannelSender implements ChannelSender {

    private static final int MARKDOWN_MAX = 20000;

    private final RestTemplate restTemplate;
    private final PushChannelValidator channelValidator;
    private final ProviderResponseValidator responseValidator;

    public DingTalkChannelSender(@Qualifier("pushRestTemplate") RestTemplate restTemplate,
                                 PushChannelValidator channelValidator,
                                 ProviderResponseValidator responseValidator) {
        this.restTemplate = restTemplate;
        this.channelValidator = channelValidator;
        this.responseValidator = responseValidator;
    }

    @Override
    public String type() { return "dingtalk"; }

    @Override
    public void send(PushChannel channel, Report report) throws Exception {
        channelValidator.validateForSend(channel);
        String url = channel.getTarget();
        if (channel.getSecret() != null && !channel.getSecret().isBlank()) {
            long ts = System.currentTimeMillis();
            String sign = sign(ts, channel.getSecret());
            String sep = url.contains("?") ? "&" : "?";
            url = url + sep + "timestamp=" + ts
                    + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "markdown");
        Map<String, Object> md = new HashMap<>();
        md.put("title", report.getTitle());
        md.put("text", truncate("## " + report.getTitle() + "\n\n" + report.getContent(), MARKDOWN_MAX));
        msg.put("markdown", md);

        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(url, msg, String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("钉钉请求失败");
        }
        responseValidator.requireSuccess(type(), response);
        log.info("钉钉推送成功 channel_id={} report_id={}", channel.getId(), report.getId());
    }

    private String sign(long timestamp, String secret) throws Exception {
        String toSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] data = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(data);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
