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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 每分钟扫描所有订阅，命中 morning_time / evening_time == 当前时间(HH:mm) 的用户，
 * 分派对应版次的最新简报。
 *
 * 幂等：每个北京时间日期、版次、用户和渠道仅认领一次持久化推送记录。
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

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        LocalTime currentTime = now.toLocalTime().withSecond(0).withNano(0);
        LocalDate currentDate = now.toLocalDate();

        dispatchEdition("morning", currentTime, currentDate);
        dispatchEdition("evening", currentTime, currentDate);
    }

    void dispatchEdition(String edition, LocalTime now, LocalDate date) {
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

        Report report = reportService.getLatestByEditionForDate(edition, date);
        if (report == null) {
            log.warn("有 {} 个用户订阅了 {} 版但暂无对应简报", due.size(), edition);
            return;
        }

        ReportPersonalizationService.PreparedReport prepared = reportPersonalizationService.prepare(report);
        for (Subscription s : due) {
            try {
                List<String> interests = subscriptionPreferences.enabledTopics(s, edition);
                Report personalized = reportPersonalizationService.personalize(prepared, interests);
                PushDispatcher.DispatchResult r = pushDispatcher.dispatchScheduled(
                        s.getUserId(), personalized, edition, date);
                log.info("[{}] user={} interests={} 分发结果 total={} ok={} fail={} skipped={}",
                        edition, s.getUserId(), interests.size(), r.total(), r.ok(), r.fail(), r.skipped());
            } catch (Exception e) {
                log.error("[{}] user={} 分发异常", edition, s.getUserId(), e);
            }
        }
    }
}
