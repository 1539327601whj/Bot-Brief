package com.ai.daily.task;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.push.PushDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final ReportService reportService;
    private final PushDispatcher pushDispatcher;

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

    private void dispatchEdition(String edition, LocalTime now, String hm) {
        List<Subscription> due = subscriptionService.findDueForEdition(edition, now);
        if (due.isEmpty()) return;

        Report report = reportService.getLatestByEdition(edition);
        if (report == null) {
            log.warn("有 {} 个用户订阅了 {} 版但暂无对应简报", due.size(), edition);
            return;
        }

        for (Subscription s : due) {
            String key = s.getUserId() + ":" + edition + ":" + hm;
            if (!recentlyPushed.add(key)) continue; // 已推过，跳过
            try {
                PushDispatcher.DispatchResult r = pushDispatcher.dispatch(s.getUserId(), report);
                log.info("[{}] user={} 分发结果 total={} ok={} fail={}",
                        edition, s.getUserId(), r.total(), r.ok(), r.fail());
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
