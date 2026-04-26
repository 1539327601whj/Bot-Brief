package com.ai.daily.service;

import com.ai.daily.entity.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业微信机器人推送服务
 */
@Slf4j
@Service
public class WeChatPushService {

    @Autowired
    private ReportService reportService;

    /**
     * 推送简报到企业微信群
     */
    public boolean pushToWeChat(String webhookUrl) {
        try {
            // 获取最新简报
            Report report = reportService.getLatestReport();
            if (report == null) {
                log.warn("没有可推送的简报");
                return false;
            }

            RestTemplate restTemplate = new RestTemplate();
            
            // 构建消息内容
            Map<String, Object> message = new HashMap<>();
            message.put("msgtype", "markdown");
            
            Map<String, Object> content = new HashMap<>();
            String markdown = buildMarkdownContent(report);
            content.put("content", markdown);
            message.put("markdown", content);

            restTemplate.postForEntity(webhookUrl, message, String.class);
            log.info("企业微信推送成功，简报ID: {}", report.getId());
            return true;
        } catch (Exception e) {
            log.error("企业微信推送失败: {}", e.getMessage());
            return false;
        }
    }

    private String buildMarkdownContent(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 **").append(report.getTitle()).append("**\n\n");
        sb.append(report.getContent());
        return sb.toString();
    }
}
