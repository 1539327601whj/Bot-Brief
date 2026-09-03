package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelValidator;
import com.ai.daily.util.MarkdownUtils;
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
        String body = MarkdownUtils.toSimpleHtml(
                PushReportFormat.bodyWithoutLeadTitle(report.getTitle(), report.getContent()));
        if (body.isBlank()) {
            body = "<p style=\"margin:0 0 12px;color:#4b5563;font-size:15px;line-height:1.8;\">"
                    + escape(report.getSummary()) + "</p>";
        }
        String html = "<div style=\"margin:0;padding:0;background:#f3f4f8;\">"
                + "<div style=\"max-width:680px;margin:0 auto;padding:24px 16px;\">"
                + "<div style=\"background:#ffffff;border:1px solid #e5e7eb;border-radius:14px;padding:28px 28px 32px;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;\">"
                + "<p style=\"margin:0 0 8px;color:#6366f1;font-size:11px;font-weight:700;letter-spacing:0.16em;\">BRIEFMIND 日报</p>"
                + "<h1 style=\"margin:0 0 20px;color:#111827;font-size:24px;font-weight:750;line-height:1.25;letter-spacing:-0.03em;\">"
                + escape(report.getTitle()) + "</h1>"
                + body
                + "</div></div></div>";
        h.setText(html, true);
        mailSender.send(msg);
        log.info("邮件推送成功 channel_id={} report_id={}", channel.getId(), report.getId());
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
