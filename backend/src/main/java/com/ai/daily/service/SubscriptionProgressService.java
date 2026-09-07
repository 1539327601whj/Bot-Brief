package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.dto.SubscriptionTodayStatusDTO;
import com.ai.daily.entity.OpsHeartbeat;
import com.ai.daily.entity.PushLog;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.TopicGenerationStatus;
import com.ai.daily.mapper.TopicSectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionProgressService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MISS_LOOKBACK_DAYS = 7;
    private static final int PUSH_CATCH_UP_HOURS = 3;

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final TopicSectionMapper topicSectionMapper;
    private final TopicGenerationStatusService generationStatusService;
    private final ReportService reportService;
    private final OpsHeartbeatService heartbeatService;
    private final SubscribedTopicService subscribedTopicService;
    private final PushLogService pushLogService;
    private final ReportQueryService reportQueryService;

    @Value("${report.generation-lead-minutes:30}")
    private int leadMinutes;

    @Value("${report.on-time-lead-minutes:5}")
    private int onTimeLeadMinutes;

    public SubscriptionTodayStatusDTO todayStatus(Long userId) {
        LocalDate today = LocalDate.now(BEIJING);
        LocalTime now = LocalTime.now(BEIJING).withSecond(0).withNano(0);
        return todayStatus(userId, today, now);
    }

    SubscriptionTodayStatusDTO todayStatus(Long userId, LocalDate today, LocalTime now) {
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
        if (subscription == null || !subscriptionPreferences.hasActiveTopics(subscription)) {
            return dto;
        }
        for (SubscriptionDTO.TopicScheduleItemDTO item : subscriptionPreferences.enabledTopicItemsOn(subscription, today)) {
            dto.getItems().add(itemStatus(userId, today, now, subscription, item));
        }
        dto.getRecentMisses().addAll(recentMisses(userId, subscription, today, now));
        return dto;
    }

    private SubscriptionTodayStatusDTO.ItemStatusDTO itemStatus(
            Long userId, LocalDate today, LocalTime now, Subscription subscription,
            SubscriptionDTO.TopicScheduleItemDTO item) {
        LocalTime readyAt = ReportWindows.parse(item.getTime()).withSecond(0).withNano(0);
        String window = ReportWindows.of(readyAt);
        String topic = item.getTopic().trim();
        SubscriptionTodayStatusDTO.ItemStatusDTO row = new SubscriptionTodayStatusDTO.ItemStatusDTO();
        row.setDate(today.toString());
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
            applyDeliveryStatus(row, userId, today, item);
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
            SubscriptionDTO.TopicScheduleItemDTO item) {
        List<Long> bound = ChannelIds.coerceAll(item.getChannelIds());
        if (bound.isEmpty()) {
            row.setStatus("delivered");
            row.setLabel("已生成");
            row.setMessage("网页已可查看（这个时刻没绑渠道，只出网页）");
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

    private List<SubscriptionTodayStatusDTO.ItemStatusDTO> recentMisses(
            Long userId, Subscription subscription, LocalDate today, LocalTime now) {
        List<SubscriptionTodayStatusDTO.ItemStatusDTO> misses = new ArrayList<>();
        LocalDateTime current = LocalDateTime.of(today, now);
        for (int offset = 0; offset < MISS_LOOKBACK_DAYS; offset++) {
            LocalDate date = today.minusDays(offset);
            for (SubscriptionDTO.TopicScheduleItemDTO item : subscriptionPreferences.enabledTopicItemsOn(subscription, date)) {
                SubscriptionTodayStatusDTO.ItemStatusDTO miss = missIfUnwritten(userId, date, item, current);
                if (miss != null) misses.add(miss);
            }
        }
        return misses;
    }

    private SubscriptionTodayStatusDTO.ItemStatusDTO missIfUnwritten(
            Long userId, LocalDate date, SubscriptionDTO.TopicScheduleItemDTO item, LocalDateTime current) {
        LocalTime readyAt = ReportWindows.parse(item.getTime()).withSecond(0).withNano(0);
        String window = ReportWindows.of(readyAt);
        String topic = item.getTopic().trim();
        Report assembled = reportService.getByUserEditionDateAndTime(userId, Report.PERSONAL, date, readyAt);
        if (assembled != null) return null;

        String digestEdition = TopicIntents.usePublicDigest(topic, item.getIntent())
                ? DigestTopics.publicEditionFor(topic, readyAt)
                : null;
        boolean hasSection = topicSectionMapper.findId(date, window, topic) != null
                || (digestEdition != null && reportService.publicReportExists(digestEdition, date));
        if (hasSection) return null;

        TopicGenerationStatus recorded = generationStatusService.find(date, window, topic);
        if (recorded != null && TopicGenerationStatus.READY.equals(recorded.getStatus())) {
            return null;
        }
        if (recorded != null && TopicGenerationStatus.SKIPPED_NO_NEWS.equals(recorded.getStatus())) {
            return missRow(date, topic, readyAt, window, "skipped", "未生成",
                    settledReason(recorded.getMessage(), "当天没有抓到与该主题直接相关的资讯"));
        }
        if (recorded != null && TopicGenerationStatus.FAILED.equals(recorded.getStatus())) {
            return missRow(date, topic, readyAt, window, "failed", "未生成",
                    settledReason(recorded.getMessage(), "当天生成失败"));
        }
        if (pastRetryDeadline(date, window, readyAt, current)) {
            return missRow(date, topic, readyAt, window, "failed", "未生成",
                    "到点后没有写成日报，也没有留下生成记录");
        }
        return null;
    }

    private static SubscriptionTodayStatusDTO.ItemStatusDTO missRow(
            LocalDate date, String topic, LocalTime readyAt, String window,
            String status, String label, String message) {
        SubscriptionTodayStatusDTO.ItemStatusDTO row = new SubscriptionTodayStatusDTO.ItemStatusDTO();
        row.setDate(date.toString());
        row.setTopic(topic);
        row.setTime(ReportWindows.format(readyAt));
        row.setWindow(window);
        row.setStatus(status);
        row.setLabel(label);
        row.setMessage(message);
        return row;
    }

    private static boolean pastRetryDeadline(
            LocalDate date, String window, LocalTime generateAt, LocalDateTime current) {
        LocalDateTime deadline = LocalDateTime.of(date, ReportWindows.windowEnd(window));
        if (generateAt != null) {
            LocalDateTime catchUpEnd = LocalDateTime.of(date, generateAt).plusHours(PUSH_CATCH_UP_HOURS);
            if (catchUpEnd.isAfter(deadline)) deadline = catchUpEnd;
        }
        return !current.isBefore(deadline);
    }

    private static String retryableFailureMessage(String recorded, String fallback) {
        if (recorded == null || recorded.isBlank()) return fallback;
        if (recorded.contains("再试") || recorded.contains("重试")) return recorded;
        return recorded + "。约 2 分钟后再试，写成后下一分钟会补网页和推送";
    }

    static String settledReason(String recorded, String fallback) {
        if (recorded == null || recorded.isBlank()) return fallback;
        String text = recorded.trim();
        int retryAt = indexOfRetryHint(text);
        if (retryAt > 0) {
            text = text.substring(0, retryAt).replaceAll("[。；;，,\\s]+$", "");
        }
        return text.isBlank() ? fallback : text;
    }

    private static int indexOfRetryHint(String text) {
        int retry = text.indexOf("约 2 分钟后再");
        if (retry >= 0) return retry;
        retry = text.indexOf("再试");
        return retry >= 0 ? retry : -1;
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
