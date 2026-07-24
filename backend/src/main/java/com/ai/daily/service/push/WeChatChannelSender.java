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

import java.util.HashMap;
import java.util.Map;

/**
 * 企业微信群机器人 Markdown 推送。
 */
@Slf4j
@Service
public class WeChatChannelSender implements ChannelSender {

    private static final int MARKDOWN_MAX = 4096; // 企业微信 markdown 上限

    private final RestTemplate restTemplate;
    private final PushChannelValidator channelValidator;
    private final ProviderResponseValidator responseValidator;

    public WeChatChannelSender(@Qualifier("pushRestTemplate") RestTemplate restTemplate,
                               PushChannelValidator channelValidator,
                               ProviderResponseValidator responseValidator) {
        this.restTemplate = restTemplate;
        this.channelValidator = channelValidator;
        this.responseValidator = responseValidator;
    }

    @Override
    public String type() { return "wechat"; }

    @Override
    public void send(PushChannel channel, Report report) {
        channelValidator.validateForSend(channel);
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "markdown");
        Map<String, Object> md = new HashMap<>();
        md.put("content", truncate(buildMarkdown(report), MARKDOWN_MAX));
        msg.put("markdown", md);
        ResponseEntity<String> response;
        try {
            response = restTemplate.postForEntity(channel.getTarget(), msg, String.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("企业微信请求失败");
        }
        responseValidator.requireSuccess(type(), response);
        log.info("企业微信推送成功 channel_id={} report_id={}", channel.getId(), report.getId());
    }

    private String buildMarkdown(Report report) {
        return "📋 **" + report.getTitle() + "**\n\n" + report.getContent();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }
}
