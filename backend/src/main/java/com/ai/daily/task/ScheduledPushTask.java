package com.ai.daily.task;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.ReportPersonalizationService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.push.PushDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每分钟扫描所有订阅，命中 morning_time / evening_time == 当前时间(HH:mm) 的用户，
 * 分派对应版次的最新简报。
 *
 * 幂等：同一分钟内每个 (user, edition) 只推一次（通过内存 recentlyPushed 集合，5 分钟 TTL）。
 * 时区：Asia/Shanghai（与 application.yml 的 jackson.time-zone 一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledPushTask {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final ReportService reportService;
    private final ReportPersonalizationService reportPersonalizationService;
    private final PushDispatcher pushDispatcher;
    private final UserMapper userMapper;

    /** 防重复：key = "userId:edition:HHmm"，最多保留最近 5 分钟的 key */
    private final Set<String> recentlyPushed = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        LocalTime now = ZonedDateTime.now(ZONE).toLocalTime().withSecond(0).withNano(0);
        String hm = String.format("%02d%02d", now.getHour(), now.getMinute());

        dispatchEdition("morning", now, hm);
        dispatchEdition("evening", now, hm);

        // 清理 5 分钟前的键（简单粗暴：只保留当前分钟前后各 2 分钟）
        cleanupRecentlyPushed(hm);
    }

    void dispatchEdition(String edition, LocalTime now, String hm) {
        List<Subscription> due = subscriptionService.findDueForEdition(edition, now);
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

        Report report = reportService.getLatestByEdition(edition);
        if (report == null) {
            log.warn("有 {} 个用户订阅了 {} 版但暂无对应简报", due.size(), edition);
            return;
        }

        ReportPersonalizationService.PreparedReport prepared = reportPersonalizationService.prepare(report);
        for (Subscription s : due) {
            String key = s.getUserId() + ":" + edition + ":" + hm;
            if (!recentlyPushed.add(key)) continue; // 已推过，跳过
            try {
                List<String> interests = subscriptionPreferences.enabledTopics(s, edition);
                Report personalized = reportPersonalizationService.personalize(prepared, interests);
                PushDispatcher.DispatchResult r = pushDispatcher.dispatch(s.getUserId(), personalized);
                log.info("[{}] user={} interests={} 分发结果 total={} ok={} fail={}",
                        edition, s.getUserId(), interests.size(), r.total(), r.ok(), r.fail());
            } catch (Exception e) {
                log.error("[{}] user={} 分发异常", edition, s.getUserId(), e);
            }
        }
    }

    private void cleanupRecentlyPushed(String currentHm) {
        // 简单方案：每 60 次 tick 清空一次（1 小时）；生产也可用 Caffeine 带 TTL
        if ("0000".equals(currentHm) || "1200".equals(currentHm)) {
            recentlyPushed.clear();
        }
    }
}
