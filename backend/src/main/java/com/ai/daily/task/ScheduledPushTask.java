package com.ai.daily.task;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportAssemblyService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.service.push.PushDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每分钟扫描所有订阅，命中已到点或刚错过订阅时刻的用户，分派对应版次的最新简报。
 *
 * 简报由定时脚本先入库，生成往往晚于 08:00/20:00。若只在整分精确匹配，
 * 报告未就绪时会直接放弃，当天不再补推，仪表盘「今日推送」也会一直为 0。
 *
 * 幂等：每个北京时间日期、版次、用户和渠道仅认领一次持久化推送记录。
 * 时区：Asia/Shanghai（与 application.yml 的 jackson.time-zone 一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPushTask {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration TICK_CATCH_UP_WINDOW = Duration.ofHours(3);

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final PushChannelService pushChannelService;
    private final ReportAssemblyService reportAssemblyService;
    private final PushDispatcher pushDispatcher;
    private final UserMapper userMapper;

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalTime currentTime = now.toLocalTime().withSecond(0).withNano(0);
        LocalDate currentDate = now.toLocalDate();

        dispatchEdition("morning", currentTime, currentDate, TICK_CATCH_UP_WINDOW);
        dispatchEdition("evening", currentTime, currentDate, TICK_CATCH_UP_WINDOW);
    }

    /**
     * 简报刚入库时补推：当天已过订阅时刻的用户都会尝试分发（dispatch_key 保证不重复发）。
     */
    public void catchUpEdition(String edition, LocalDate date) {
        if (!Report.isPersonalizedEdition(edition)) return;
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalDate targetDate = date != null ? date : now.toLocalDate();
        if (!targetDate.equals(now.toLocalDate())) return;
        dispatchEdition(edition, now.toLocalTime().withSecond(0).withNano(0), targetDate, null);
    }

    void dispatchEdition(String edition, LocalTime now, LocalDate date) {
        dispatchEdition(edition, now, date, TICK_CATCH_UP_WINDOW);
    }

    void dispatchEdition(String edition, LocalTime now, LocalDate date, Duration maxLateness) {
        List<Subscription> due = subscriptionService.findDueThrough(edition, now, maxLateness);
        if (due.isEmpty()) return;

        Map<Long, User> users = userMapper.selectBatchIds(
                        due.stream().map(Subscription::getUserId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        due = due.stream()
                .filter(s -> {
                    User user = users.get(s.getUserId());
                    return user != null
                            && Boolean.TRUE.equals(user.getEnabled())
                            && !User.ACCOUNT_DEMO.equals(user.getAccountType());
                })
                .toList();
        if (due.isEmpty()) return;

        for (Subscription s : due) {
            try {
                List<SubscriptionDTO.TopicScheduleItemDTO> items = subscriptionPreferences.enabledTopicItems(s, edition);
                if (items.isEmpty()) {
                    log.info("[{}] user={} 未勾选主题，跳过推送", edition, s.getUserId());
                    continue;
                }
                List<String> allTopics = items.stream().map(SubscriptionDTO.TopicScheduleItemDTO::getTopic).toList();
                Report persisted = reportAssemblyService.assembleAndPersist(s.getUserId(), edition, date, allTopics);
                if (persisted == null) {
                    log.warn("[{}] user={} 勾选了 {} 个主题但暂无可拼装段落", edition, s.getUserId(), allTopics.size());
                    continue;
                }

                List<PushChannel> channels = pushChannelService.listEnabledByUser(s.getUserId());
                Map<Long, List<String>> interestsByChannel = new java.util.LinkedHashMap<>();
                for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
                    if (item.getChannelIds() == null) {
                        channels.forEach(channel -> interestsByChannel
                                .computeIfAbsent(channel.getId(), ignored -> new java.util.ArrayList<>())
                                .add(item.getTopic()));
                    } else {
                        item.getChannelIds().forEach(channelId -> {
                            if (channels.stream().anyMatch(channel -> channel.getId().equals(channelId))) {
                                interestsByChannel.computeIfAbsent(channelId, ignored -> new java.util.ArrayList<>())
                                        .add(item.getTopic());
                            }
                        });
                    }
                }
                Map<Long, Report> reportsByChannel = new java.util.LinkedHashMap<>();
                for (Map.Entry<Long, List<String>> entry : interestsByChannel.entrySet()) {
                    Report personalized = reportAssemblyService.assembleEphemeral(
                            persisted.getId(), edition, date, entry.getValue());
                    if (personalized != null) {
                        reportsByChannel.put(entry.getKey(), personalized);
                    }
                }
                if (reportsByChannel.isEmpty()) {
                    log.warn("[{}] user={} 已拼装简报但各渠道主题均无内容", edition, s.getUserId());
                    continue;
                }
                PushDispatcher.DispatchResult r = pushDispatcher.dispatchScheduledByChannel(
                        s.getUserId(), reportsByChannel, edition, date);
                log.info("[{}] user={} interests={} channels={} 分发结果 total={} ok={} fail={} skipped={}",
                        edition, s.getUserId(), items.size(), reportsByChannel.size(), r.total(), r.ok(), r.fail(), r.skipped());
            } catch (Exception e) {
                log.error("[{}] user={} 分发异常", edition, s.getUserId(), e);
            }
        }
    }
}
