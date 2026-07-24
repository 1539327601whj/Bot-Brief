package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.PushLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按用户分发推送到所有已启用的渠道。
 * 每次分发结果写入 push_log 表，方便前端"通知记录"页展示。
 */
@Slf4j
@Component
public class PushDispatcher {

    private static final int RECORDED_ERROR_MAX = 300;

    private final Map<String, ChannelSender> senders = new HashMap<>();
    private final PushChannelService channelService;
    private final PushLogService pushLogService;

    public PushDispatcher(List<ChannelSender> allSenders,
                          PushChannelService channelService,
                          PushLogService pushLogService) {
        this.channelService = channelService;
        this.pushLogService = pushLogService;
        for (ChannelSender s : allSenders) senders.put(s.type(), s);
        log.info("PushDispatcher 已注册渠道类型: {}", senders.keySet());
    }

    /** 分发到用户所有 enabled 渠道 */
    public DispatchResult dispatch(Long userId, Report report) {
        List<PushChannel> channels = channelService.listEnabledByUser(userId);
        int ok = 0, fail = 0;
        for (PushChannel ch : channels) {
            ChannelSender sender = senders.get(ch.getChannelType());
            if (sender == null) {
                log.warn("未知渠道类型 channel_id={} report_id={}", ch.getId(), report.getId());
                pushLogService.record(userId, report.getId(), ch.getId(), ch.getChannelType(),
                        false, "未知渠道类型");
                fail++;
                continue;
            }
            try {
                sender.send(ch, report);
                pushLogService.record(userId, report.getId(), ch.getId(), ch.getChannelType(), true, null);
                ok++;
            } catch (Exception e) {
                log.warn("推送失败 channel_id={} report_id={}", ch.getId(), report.getId());
                pushLogService.record(userId, report.getId(), ch.getId(), ch.getChannelType(),
                        false, safeError(e));
                fail++;
            }
        }
        return new DispatchResult(channels.size(), ok, fail);
    }

    public DispatchResult dispatchScheduled(Long userId, Report report, String edition, LocalDate date) {
        List<PushChannel> channels = channelService.listEnabledByUser(userId);
        int ok = 0, fail = 0, skipped = 0;
        for (PushChannel channel : channels) {
            String dispatchKey = scheduledDispatchKey(userId, channel.getId(), edition, date);
            Long logId = pushLogService.claimScheduled(userId, report.getId(), channel.getId(),
                    channel.getChannelType(), dispatchKey);
            if (logId == null) {
                skipped++;
                continue;
            }
            ChannelSender sender = senders.get(channel.getChannelType());
            if (sender == null) {
                pushLogService.markFailed(logId, "未知渠道类型");
                log.warn("未知渠道类型 channel_id={} report_id={}", channel.getId(), report.getId());
                fail++;
                continue;
            }
            try {
                sender.send(channel, report);
                pushLogService.markSuccess(logId);
                ok++;
            } catch (Exception e) {
                pushLogService.markFailed(logId, safeError(e));
                log.warn("定时推送失败 channel_id={} report_id={}", channel.getId(), report.getId());
                fail++;
            }
        }
        return new DispatchResult(channels.size(), ok, fail, skipped);
    }

    /** 单渠道试推（用于前端"测试推送"按钮） */
    public void sendOne(PushChannel channel, Report report) throws Exception {
        ChannelSender sender = senders.get(channel.getChannelType());
        if (sender == null) {
            IllegalArgumentException error = new IllegalArgumentException("未知渠道类型");
            pushLogService.record(channel.getUserId(), report.getId(), channel.getId(),
                    channel.getChannelType(), false, safeError(error));
            throw error;
        }
        try {
            sender.send(channel, report);
            pushLogService.record(channel.getUserId(), report.getId(), channel.getId(),
                    channel.getChannelType(), true, null);
        } catch (Exception e) {
            String errorMessage = safeError(e);
            pushLogService.record(channel.getUserId(), report.getId(), channel.getId(),
                    channel.getChannelType(), false, errorMessage);
            throw new IllegalStateException(errorMessage);
        }
    }

    private String scheduledDispatchKey(Long userId, Long channelId, String edition, LocalDate date) {
        return "scheduled:" + date + ":" + edition + ":" + userId + ":" + channelId;
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "推送服务异常";
        String sanitized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        boolean safe = sanitized.equals("推送渠道不存在")
                || sanitized.equals("推送目标不能为空")
                || sanitized.equals("邮箱地址格式无效")
                || sanitized.equals("Webhook 地址格式无效")
                || sanitized.equals("Webhook 必须使用官方 HTTPS 地址")
                || sanitized.equals("Webhook 地址不是受支持的官方机器人地址")
                || sanitized.equals("邮件推送未配置 MAIL_USERNAME")
                || sanitized.matches("(企业微信|钉钉|飞书)(请求失败|返回空响应|返回失败|返回无效响应)")
                || sanitized.equals("未知渠道类型");
        if (!safe) return error instanceof IllegalArgumentException ? "推送参数无效" : "推送服务异常";
        return sanitized.length() > RECORDED_ERROR_MAX
                ? sanitized.substring(0, RECORDED_ERROR_MAX)
                : sanitized;
    }

    public record DispatchResult(int total, int ok, int fail, int skipped) {
        public DispatchResult(int total, int ok, int fail) {
            this(total, ok, fail, 0);
        }
    }
}
