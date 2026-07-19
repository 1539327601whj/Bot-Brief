package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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

    @Override
    public String type() { return "wechat"; }

    @Override
    public void send(PushChannel channel, Report report) {
        RestTemplate rt = new RestTemplate();
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "markdown");
        Map<String, Object> md = new HashMap<>();
        md.put("content", truncate(buildMarkdown(report), MARKDOWN_MAX));
        msg.put("markdown", md);
        rt.postForEntity(channel.getTarget(), msg, String.class);
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
