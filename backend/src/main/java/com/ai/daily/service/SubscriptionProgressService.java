package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.dto.SubscriptionTodayStatusDTO;
import com.ai.daily.entity.OpsHeartbeat;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.PushLog;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.TopicGenerationStatus;
import com.ai.daily.mapper.TopicSectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionProgressService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final TopicSectionMapper topicSectionMapper;
    private final TopicGenerationStatusService generationStatusService;
    private final ReportService reportService;
    private final OpsHeartbeatService heartbeatService;
    private final SubscribedTopicService subscribedTopicService;
    private final PushLogService pushLogService;
    private final PushChannelService pushChannelService;
    private final ReportQueryService reportQueryService;

    @Value("${report.generation-lead-minutes:30}")
    private int leadMinutes;

    @Value("${report.on-time-lead-minutes:5}")
    private int onTimeLeadMinutes;

    public SubscriptionTodayStatusDTO todayStatus(Long userId) {
        LocalDate today = LocalDate.now(BEIJING);
        LocalTime now = LocalTime.now(BEIJING).withSecond(0).withNano(0);
        SubscriptionTodayStatusDTO dto = new SubscriptionTodayStatusDTO();
        dto.setDate(today.toString());
        dto.setLeadMinutes(Math.max(0, leadMinutes));
        dto.setOnTimeLeadMinutes(Math.max(1, onTimeLeadMinutes));
        dto.setEarliestOnTime(ReportWindows.format(ReportWindows.earliestOnTime(now, onTimeLeadMinutes)));
        dto.setPoller(pollerStatus());
        if (userId == null) return dto;

        try {
            reportQueryService.ensureTodayAssembled(userId);
        } catch (Exception ignored) {
        }

        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        if (subscription == null || !Boolean.TRUE.equals(subscription.getEnabled())) {
            return dto;
        }
        for (SubscriptionDTO.TopicScheduleItemDTO item : subscriptionPreferences.enabledTopicItemsOn(subscription, today)) {
            dto.getItems().add(itemStatus(userId, today, now, subscription, item));
        }
        return dto;
    }

    private SubscriptionTodayStatusDTO.ItemStatusDTO itemStatus(
            Long userId, LocalDate today, LocalTime now, Subscription subscription,
            SubscriptionDTO.TopicScheduleItemDTO item) {
        LocalTime readyAt = ReportWindows.parse(item.getTime()).withSecond(0).withNano(0);
        String window = ReportWindows.of(readyAt);
        String topic = item.getTopic().trim();
        SubscriptionTodayStatusDTO.ItemStatusDTO row = new SubscriptionTodayStatusDTO.ItemStatusDTO();
        row.setTopic(topic);
        row.setTime(ReportWindows.format(readyAt));
        row.setWindow(window);

        Report assembled = reportService.getByUserEditionDateAndTime(userId, Report.PERSONAL, today, readyAt);
        String digestEdition = TopicIntents.usePublicDigest(topic, item.getIntent())
                ? DigestTopics.publicEditionFor(topic, readyAt)
                : null;
        boolean hasSection = topicSectionMapper.findId(today, window, topic) != null
                || (digestEdition != null && reportService.publicReportExists(digestEdition, today));
        TopicGenerationStatus recorded = generationStatusService.find(today, window, topic);

        if (assembled != null) {
            applyDeliveryStatus(row, userId, today, subscription, item);
            return row;
        }
        if (hasSection || (recorded != null && TopicGenerationStatus.READY.equals(recorded.getStatus()))) {
            row.setStatus("ready");
            row.setLabel("已备好");
            row.setMessage(now.isBefore(readyAt)
                    ? "内容已写好，到 " + row.getTime() + " 会显示并推送"
                    : "内容已写好，打开首页即可查看");
            return row;
        }
        if (recorded != null && TopicGenerationStatus.SKIPPED_NO_NEWS.equals(recorded.getStatus())) {
            row.setStatus("skipped");
            row.setLabel("无匹配资讯");
            row.setMessage(retryableFailureMessage(recorded.getMessage(),
                    "还没抓到匹配资讯，约 2 分钟后再抓；写成后下一分钟会补网页和推送"));
            return row;
        }
        if (recorded != null && TopicGenerationStatus.FAILED.equals(recorded.getStatus())) {
            row.setStatus("failed");
            row.setLabel("生成失败");
            row.setMessage(retryableFailureMessage(recorded.getMessage(),
                    "生成失败，约 2 分钟后再试；写成后下一分钟会补网页和推送"));
            return row;
        }

        LocalTime startAt = subscribedTopicService.startAt(readyAt, topic);
        if (startAt.isAfter(now)) {
            row.setStatus("upcoming");
            row.setLabel("已预约");
            row.setMessage("现在保存即可，不必等到准备时间。系统大约 "
                    + ReportWindows.format(startAt) + " 开始生成，" + row.getTime() + " 准时在网页展示并推送");
            return row;
        }
        row.setStatus("preparing");
        row.setLabel("准备中");
        row.setMessage("已过准备起点，正在或即将抓取。通常 2–5 分钟写完；" + row.getTime() + " 准点推送，错过整分会在随后补推");
        return row;
    }

    private void applyDeliveryStatus(
            SubscriptionTodayStatusDTO.ItemStatusDTO row,
            Long userId,
            LocalDate today,
            Subscription subscription,
            SubscriptionDTO.TopicScheduleItemDTO item) {
        List<Long> bound = effectiveChannelIds(userId, today, subscription, item);
        if (bound.isEmpty()) {
            row.setStatus("delivered");
            row.setLabel("已生成");
            row.setMessage("网页已可查看（没有可用推送账号，不会外推）");
            return;
        }
        String prefix = "scheduled:" + today + ":" + row.getTime() + ":" + userId + ":";
        List<PushLog> slotLogs = pushLogService.recentByUser(userId, 200).stream()
                .filter(log -> log.getDispatchKey() != null && log.getDispatchKey().startsWith(prefix))
                .toList();
        int success = 0;
        int failed = 0;
        int sending = 0;
        for (Long channelId : bound) {
            PushLog latest = slotLogs.stream()
                    .filter(log -> ChannelIds.same(log.getChannelId(), channelId))
                    .findFirst()
                    .orElse(null);
            if (latest == null) continue;
            if ("success".equals(latest.getStatus())) success++;
            else if ("failed".equals(latest.getStatus())) failed++;
            else sending++;
        }
        if (success >= bound.size()) {
            row.setStatus("pushed");
            row.setLabel("已推送");
            row.setMessage("网页已可查看，" + bound.size() + " 个渠道已投递");
            return;
        }
        if (success > 0 && failed == 0 && sending == 0) {
            row.setStatus("pushed");
            row.setLabel("已推送");
            row.setMessage("网页已可查看，已投递 " + success + " / " + bound.size() + " 个渠道");
            return;
        }
        if (failed > 0 && success == 0) {
            row.setStatus("push_failed");
            row.setLabel("推送失败");
            row.setMessage("网页已可查看，渠道投递失败，系统会继续补推。也可到通知记录查看原因");
            return;
        }
        if (success > 0) {
            row.setStatus("push_partial");
            row.setLabel("部分推送");
            row.setMessage("网页已可查看，已投递 " + success + " / " + bound.size() + " 个渠道，失败的会继续补推");
            return;
        }
        if (sending > 0) {
            row.setStatus("web_ready");
            row.setLabel("推送中");
            row.setMessage("网页已可查看，正在投递绑定渠道");
            return;
        }
        row.setStatus("web_ready");
        row.setLabel("网页已出");
        row.setMessage("网页已可查看，绑定渠道还没投递成功；打开本页或下一分钟会补推");
    }

    private List<Long> effectiveChannelIds(
            Long userId, LocalDate today, Subscription subscription, SubscriptionDTO.TopicScheduleItemDTO item) {
        List<Long> bound = ChannelIds.coerceAll(item.getChannelIds());
        if (!bound.isEmpty()) return bound;
        bound = subscriptionPreferences.boundChannelIds(subscription, today);
        if (!bound.isEmpty()) return bound;
        return pushChannelService.listEnabledByUser(userId).stream()
                .map(PushChannel::getId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private static String retryableFailureMessage(String recorded, String fallback) {
        if (recorded == null || recorded.isBlank()) return fallback;
        if (recorded.contains("再试") || recorded.contains("重试")) return recorded;
        return recorded + "。约 2 分钟后再试，写成后下一分钟会补网页和推送";
    }

    private SubscriptionTodayStatusDTO.PollerStatusDTO pollerStatus() {
        OpsHeartbeat beat = heartbeatService.find(OpsHeartbeatService.POLLER);
        SubscriptionTodayStatusDTO.PollerStatusDTO dto = new SubscriptionTodayStatusDTO.PollerStatusDTO();
        dto.setHealthy(heartbeatService.isFresh(beat));
        if (beat != null && beat.getLastSeen() != null) {
            dto.setLastSeen(beat.getLastSeen().format(DATE_TIME));
            dto.setDetail(beat.getDetail());
        }
        return dto;
    }
}
