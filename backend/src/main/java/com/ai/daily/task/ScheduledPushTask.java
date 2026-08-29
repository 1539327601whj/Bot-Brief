package com.ai.daily.task;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportAssemblyService;
import com.ai.daily.service.ReportWindows;
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
 * 每分钟扫描订阅，按用户选定的时刻拼装并推送。
 * 同一用户同一分钟只发一封；不同时刻各发一封。
 * 幂等键包含展示时刻。未绑定渠道的主题只入库网页简报。
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
        dispatchDue(now.toLocalTime().withSecond(0).withNano(0), now.toLocalDate(), TICK_CATCH_UP_WINDOW);
    }

    public void catchUpToday(LocalDate date) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalDate targetDate = date != null ? date : now.toLocalDate();
        if (!targetDate.equals(now.toLocalDate())) return;
        dispatchDue(now.toLocalTime().withSecond(0).withNano(0), targetDate, null);
    }

    void dispatchDue(LocalTime now, LocalDate date) {
        dispatchDue(now, date, TICK_CATCH_UP_WINDOW);
    }

    void dispatchDue(LocalTime now, LocalDate date, Duration maxLateness) {
        List<Subscription> due = subscriptionService.findDueThrough(now, maxLateness);
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

        for (Subscription subscription : due) {
            for (LocalTime displayTime : subscriptionPreferences.dueDisplayTimes(subscription, now, maxLateness)) {
                dispatchAt(subscription, date, displayTime);
            }
        }
    }

    private void dispatchAt(Subscription subscription, LocalDate date, LocalTime displayTime) {
        String slot = ReportWindows.format(displayTime);
        try {
            List<SubscriptionDTO.TopicScheduleItemDTO> items =
                    subscriptionPreferences.enabledTopicItemsAt(subscription, displayTime);
            if (items.isEmpty()) {
                log.info("[{}] user={} 该时刻没有主题，跳过", slot, subscription.getUserId());
                return;
            }
            List<String> allTopics = items.stream().map(SubscriptionDTO.TopicScheduleItemDTO::getTopic).toList();
            Report persisted = reportAssemblyService.assembleAndPersist(
                    subscription.getUserId(), date, displayTime, allTopics);
            if (persisted == null) {
                log.warn("[{}] user={} 勾选了 {} 个主题但暂无可拼装段落", slot, subscription.getUserId(), allTopics.size());
                return;
            }

            List<PushChannel> channels = pushChannelService.listEnabledByUser(subscription.getUserId());
            Map<Long, List<String>> interestsByChannel = new java.util.LinkedHashMap<>();
            for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
                if (item.getChannelIds() == null || item.getChannelIds().isEmpty()) continue;
                item.getChannelIds().forEach(channelId -> {
                    if (channels.stream().anyMatch(channel -> channel.getId().equals(channelId))) {
                        interestsByChannel.computeIfAbsent(channelId, ignored -> new java.util.ArrayList<>())
                                .add(item.getTopic());
                    }
                });
            }
            if (interestsByChannel.isEmpty()) {
                log.info("[{}] user={} 已入库网页简报，未绑定渠道", slot, subscription.getUserId());
                return;
            }
            Map<Long, Report> reportsByChannel = new java.util.LinkedHashMap<>();
            for (Map.Entry<Long, List<String>> entry : interestsByChannel.entrySet()) {
                Report personalized = reportAssemblyService.assembleEphemeral(
                        persisted.getId(), date, displayTime, entry.getValue());
                if (personalized != null) {
                    reportsByChannel.put(entry.getKey(), personalized);
                }
            }
            if (reportsByChannel.isEmpty()) {
                log.warn("[{}] user={} 已拼装简报但各渠道主题均无内容", slot, subscription.getUserId());
                return;
            }
            PushDispatcher.DispatchResult result = pushDispatcher.dispatchScheduledByChannel(
                    subscription.getUserId(), reportsByChannel, slot, date);
            log.info("[{}] user={} interests={} channels={} 分发结果 total={} ok={} fail={} skipped={}",
                    slot, subscription.getUserId(), items.size(), reportsByChannel.size(),
                    result.total(), result.ok(), result.fail(), result.skipped());
        } catch (Exception e) {
            log.error("[{}] user={} 分发异常", slot, subscription.getUserId(), e);
        }
    }
}
