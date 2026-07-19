package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.PushLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    private final Map<String, ChannelSender> senders = new HashMap<>();

    @Autowired
    private PushChannelService channelService;

    @Autowired
    private PushLogService pushLogService;

    @Autowired
    public PushDispatcher(List<ChannelSender> allSenders) {
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
                log.warn("未知渠道类型 {} channel_id={}", ch.getChannelType(), ch.getId());
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
                log.warn("推送失败 user={} channel_id={} type={} err={}",
                        userId, ch.getId(), ch.getChannelType(), e.getMessage());
                pushLogService.record(userId, report.getId(), ch.getId(), ch.getChannelType(),
                        false, e.getMessage());
                fail++;
            }
        }
        return new DispatchResult(channels.size(), ok, fail);
    }

    /** 单渠道试推（用于前端"测试推送"按钮） */
    public void sendOne(PushChannel channel, Report report) throws Exception {
        ChannelSender sender = senders.get(channel.getChannelType());
        if (sender == null) throw new IllegalArgumentException("未知渠道类型: " + channel.getChannelType());
        sender.send(channel, report);
        pushLogService.record(channel.getUserId(), report.getId(), channel.getId(),
                channel.getChannelType(), true, null);
    }

    public record DispatchResult(int total, int ok, int fail) {}
}
