package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelValidator;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailPushService implements ChannelSender {

    private final JavaMailSender mailSender;
    private final PushChannelValidator channelValidator;

    @Value("${spring.mail.username:}")
    private String from;

    @Value("${mail-push.from-name:BriefMind}")
    private String fromName;

    @Override
    public String type() { return "email"; }

    @Override
    public void send(PushChannel channel, Report report) throws Exception {
        channelValidator.validateForSend(channel);
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("邮件推送未配置 MAIL_USERNAME");
        }
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
        h.setFrom(from, fromName);
        h.setTo(channel.getTarget());
        h.setSubject(report.getTitle());
        // Markdown 简单包裹 <pre> 保留格式；后续可换成 flexmark 转 HTML
        String html = "<div style='font-family:monospace,Menlo,Consolas;line-height:1.6;'>"
                + "<h2>" + escape(report.getTitle()) + "</h2>"
                + "<pre style='white-space:pre-wrap;word-wrap:break-word;'>"
                + escape(report.getContent())
                + "</pre></div>";
        h.setText(html, true);
        mailSender.send(msg);
        log.info("邮件推送成功 channel_id={} report_id={}", channel.getId(), report.getId());
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
