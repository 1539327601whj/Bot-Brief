package com.ai.daily.task;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.ChannelIds;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportAssemblyService;
import com.ai.daily.service.ReportWindows;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.push.PushDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每分钟扫描订阅，按用户选定的时刻拼装并推送。
 * 同一用户同一分钟只发一封；不同时刻各发一封。
 * 幂等键包含展示时刻。某个时刻没点选渠道就只出网页，不沿用其它主题，也不推全部账号。
 */
@Slf4j
@Component
public class ScheduledPushTask {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final PushChannelService pushChannelService;
    private final ReportAssemblyService reportAssemblyService;
    private final PushDispatcher pushDispatcher;
    private final UserMapper userMapper;
    private final Executor pushDispatchExecutor;

    public ScheduledPushTask(SubscriptionService subscriptionService,
                             SubscriptionPreferences subscriptionPreferences,
                             PushChannelService pushChannelService,
                             ReportAssemblyService reportAssemblyService,
                             PushDispatcher pushDispatcher,
                             UserMapper userMapper,
                             @Qualifier("pushDispatchExecutor") Executor pushDispatchExecutor) {
        this.subscriptionService = subscriptionService;
        this.subscriptionPreferences = subscriptionPreferences;
        this.pushChannelService = pushChannelService;
        this.reportAssemblyService = reportAssemblyService;
        this.pushDispatcher = pushDispatcher;
        this.userMapper = userMapper;
        this.pushDispatchExecutor = pushDispatchExecutor != null ? pushDispatchExecutor : Runnable::run;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        dispatchDue(now.toLocalTime().withSecond(0).withNano(0), now.toLocalDate(), null);
    }

    public void catchUpToday(LocalDate date) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalDate targetDate = date != null ? date : now.toLocalDate();
        if (!targetDate.equals(now.toLocalDate())) return;
        dispatchDue(now.toLocalTime().withSecond(0).withNano(0), targetDate, null);
    }

    void dispatchDue(LocalTime now, LocalDate date) {
        dispatchDue(now, date, null);
    }

    public void catchUpUser(Subscription subscription, LocalDate date, LocalTime displayTime) {
        if (subscription == null || date == null || displayTime == null) return;
        LocalTime now = LocalTime.now(ZONE).withSecond(0).withNano(0);
        if (displayTime.withSecond(0).withNano(0).isAfter(now)) return;
        User user = userMapper.selectById(subscription.getUserId());
        if (user == null
                || !Boolean.TRUE.equals(user.getEnabled())
                || User.ACCOUNT_DEMO.equals(user.getAccountType())) {
            return;
        }
        dispatchAt(subscription, date, displayTime);
    }

    void dispatchDue(LocalTime now, LocalDate date, Duration maxLateness) {
        List<Subscription> due = subscriptionService.findDueThrough(now, date, maxLateness);
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
            for (LocalTime displayTime : subscriptionPreferences.dueDisplayTimes(subscription, now, maxLateness, date)) {
                dispatchAt(subscription, date, displayTime);
            }
        }
    }

    private void dispatchAt(Subscription subscription, LocalDate date, LocalTime displayTime) {
        String slot = ReportWindows.format(displayTime);
        try {
            List<SubscriptionDTO.TopicScheduleItemDTO> items =
                    subscriptionPreferences.enabledTopicItemsAt(subscription, displayTime, date);
            if (items.isEmpty()) {
                log.info("[{}] user={} 该时刻没有主题，跳过", slot, subscription.getUserId());
                return;
            }
            List<String> allTopics = items.stream().map(SubscriptionDTO.TopicScheduleItemDTO::getTopic).toList();
            Report persisted = reportAssemblyService.assembleAndPersistItems(
                    subscription.getUserId(), date, displayTime, items);
            if (persisted == null) {
                log.warn("[{}] user={} 勾选了 {} 个主题但暂无可拼装段落", slot, subscription.getUserId(), allTopics.size());
                return;
            }

            List<PushChannel> channels = pushChannelService.listEnabledByUser(subscription.getUserId());
            Map<Long, List<SubscriptionDTO.TopicScheduleItemDTO>> itemsByChannel = new LinkedHashMap<>();
            List<Long> boundIds = new ArrayList<>();
            for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
                List<Long> channelIds = ChannelIds.coerceAll(item.getChannelIds());
                boundIds.addAll(channelIds);
                for (Long channelId : channelIds) {
                    PushChannel matched = ChannelIds.find(channels, channelId);
                    if (matched != null) {
                        itemsByChannel.computeIfAbsent(matched.getId(), ignored -> new ArrayList<>()).add(item);
                    }
                }
            }
            if (itemsByChannel.isEmpty()) {
                if (!boundIds.isEmpty()) {
                    log.warn("[{}] user={} 已入库网页简报，绑定渠道 {} 与已启用渠道不匹配",
                            slot, subscription.getUserId(), boundIds);
                } else {
                    log.info("[{}] user={} 已入库网页简报，未绑定渠道，仅网页", slot, subscription.getUserId());
                }
                return;
            }
            Map<Long, Report> reportsByChannel = new LinkedHashMap<>();
            for (Map.Entry<Long, List<SubscriptionDTO.TopicScheduleItemDTO>> entry : itemsByChannel.entrySet()) {
                Report personalized = reportAssemblyService.assembleEphemeralItems(
                        persisted.getId(), date, displayTime, entry.getValue());
                reportsByChannel.put(entry.getKey(), personalized != null ? personalized : persisted);
            }
            final Map<Long, Report> outbound = Map.copyOf(reportsByChannel);
            pushDispatchExecutor.execute(() -> {
                try {
                    PushDispatcher.DispatchResult result = pushDispatcher.dispatchScheduledByChannel(
                            subscription.getUserId(), outbound, slot, date);
                    log.info("[{}] user={} interests={} channels={} 分发结果 total={} ok={} fail={} skipped={}",
                            slot, subscription.getUserId(), items.size(), outbound.size(),
                            result.total(), result.ok(), result.fail(), result.skipped());
                } catch (Exception e) {
                    log.error("[{}] user={} 异步分发异常", slot, subscription.getUserId(), e);
                }
            });
        } catch (Exception e) {
            log.error("[{}] user={} 分发异常", slot, subscription.getUserId(), e);
        }
    }
}
