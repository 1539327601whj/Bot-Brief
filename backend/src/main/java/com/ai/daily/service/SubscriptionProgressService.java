package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.dto.SubscriptionTodayStatusDTO;
import com.ai.daily.entity.OpsHeartbeat;
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

        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        if (subscription == null || !Boolean.TRUE.equals(subscription.getEnabled())) {
            return dto;
        }
        for (SubscriptionDTO.TopicScheduleItemDTO item : subscriptionPreferences.enabledTopicItemsOn(subscription, today)) {
            dto.getItems().add(itemStatus(userId, today, now, item));
        }
        return dto;
    }

    private SubscriptionTodayStatusDTO.ItemStatusDTO itemStatus(
            Long userId, LocalDate today, LocalTime now, SubscriptionDTO.TopicScheduleItemDTO item) {
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
            row.setStatus("delivered");
            row.setLabel("已生成");
            row.setMessage("网页已可查看，绑定的渠道会按此时刻投递");
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
