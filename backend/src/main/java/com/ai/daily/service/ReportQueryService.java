package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.task.ScheduledPushTask;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportQueryService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final ReportService reportService;
    private final ReportAssemblyService reportAssemblyService;
    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final ScheduledPushTask scheduledPushTask;

    public void ensureTodayAssembled(Long userId) {
        if (userId == null) return;
        assembleDueForToday(userId);
    }

    public Report getLatest(Long userId, boolean demo, String edition) {
        return getLatest(userId, demo, false, edition);
    }

    public Report getLatest(Long userId, boolean demo, boolean allowPublicDigest, String edition) {
        if (demo) {
            if (edition == null || edition.isBlank()) {
                return reportService.getLatestReport();
            }
            return reportService.getLatestByEdition(edition);
        }
        if (edition != null && !edition.isBlank()) {
            if (allowPublicDigest && (Report.isSharedPublicEdition(edition) || Report.isPublicDigest(edition))) {
                return releasedOrNull(reportService.getLatestByEdition(edition));
            }
            if (userId == null) return null;
            if (Report.isPersonalizedEdition(edition)) {
                safeEnsureTodayAssembled(userId);
                return reportService.getLatestForUser(userId, Report.PERSONAL);
            }
            return null;
        }
        if (userId == null) return null;
        safeEnsureTodayAssembled(userId);
        Report mine = releasedOrNull(reportService.getLatestForUser(userId, Report.PERSONAL));
        Report market = allowPublicDigest ? releasedOrNull(reportService.getLatestPublicMarketWatch()) : null;
        Report digest = allowPublicDigest ? latestPublicDigest() : null;
        return newest(newest(mine, market), digest);
    }

    public Report getById(Long userId, boolean demo, Long id) {
        return getById(userId, demo, false, id);
    }

    public Report getById(Long userId, boolean demo, boolean allowPublicDigest, Long id) {
        Report report = reportService.getById(id);
        if (report == null) return null;
        if (demo) {
            return Report.isPublicOwner(report.getUserId()) ? report : null;
        }
        if (userId != null && userId.equals(report.getUserId())) {
            return releasedOrNull(report);
        }
        if (!Report.isPublicOwner(report.getUserId())) return null;
        if (!allowPublicDigest) return null;
        if (!(Report.isSharedPublicEdition(report.getEdition()) || Report.isPublicDigest(report.getEdition()))) {
            return null;
        }
        return releasedOrNull(report);
    }

    public Page<Report> pageVisible(
            Long userId,
            boolean demo,
            Page<Report> page,
            String edition,
            LocalDateTime start,
            LocalDateTime end,
            String keyword) {
        return pageVisible(userId, demo, false, page, edition, start, end, keyword);
    }

    public Page<Report> pageVisible(
            Long userId,
            boolean demo,
            boolean allowPublicDigest,
            Page<Report> page,
            String edition,
            LocalDateTime start,
            LocalDateTime end,
            String keyword) {
        if (!demo && userId != null && !isPublicOnlyEdition(edition)) {
            safeEnsureTodayAssembled(userId);
        }
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (demo) {
            wrapper.eq(Report::getUserId, Report.PUBLIC_OWNER_ID);
            wrapper.eq(edition != null && !edition.isBlank(), Report::getEdition, edition);
        } else if (userId == null) {
            return page;
        } else if (edition != null && !edition.isBlank()) {
            if (Report.isPersonalizedEdition(edition)) {
                wrapper.eq(Report::getUserId, userId).eq(Report::getEdition, Report.PERSONAL);
            } else if (allowPublicDigest && Report.isSharedPublicEdition(edition)) {
                wrapper.eq(Report::getUserId, Report.PUBLIC_OWNER_ID).eq(Report::getEdition, edition);
            } else if (allowPublicDigest && Report.isPublicDigest(edition)) {
                wrapper.eq(Report::getUserId, Report.PUBLIC_OWNER_ID).eq(Report::getEdition, edition);
            } else {
                return page;
            }
        } else {
            applyOwnerScope(wrapper, userId, false, allowPublicDigest);
        }
        if (start != null) {
            wrapper.ge(Report::getCreatedAt, start);
        }
        if (end != null) {
            wrapper.le(Report::getCreatedAt, end);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(Report::getTitle, keyword).or().like(Report::getSummary, keyword));
        }
        if (!demo) {
            applyReleaseScope(wrapper);
        }
        wrapper.orderByDesc(Report::getCreatedAt);
        return reportService.page(page, wrapper);
    }

    public long countVisible(Long userId, boolean demo, LocalDateTime start, LocalDateTime end) {
        return countVisible(userId, demo, false, start, end);
    }

    public long countVisible(
            Long userId, boolean demo, boolean allowPublicDigest, LocalDateTime start, LocalDateTime end) {
        if (!demo && userId != null) {
            safeEnsureTodayAssembled(userId);
        }
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (start != null) wrapper.ge(Report::getCreatedAt, start);
        if (end != null) wrapper.lt(Report::getCreatedAt, end);
        applyOwnerScope(wrapper, userId, demo, allowPublicDigest);
        if (!demo) {
            applyReleaseScope(wrapper);
        }
        return reportService.count(wrapper);
    }

    public List<Report> listVisibleForStats(Long userId, boolean demo, LocalDateTime start) {
        return listVisibleForStats(userId, demo, false, start);
    }

    public List<Report> listVisibleForStats(
            Long userId, boolean demo, boolean allowPublicDigest, LocalDateTime start) {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .ge(start != null, Report::getCreatedAt, start)
                .select(Report::getTitle, Report::getSummary);
        applyOwnerScope(wrapper, userId, demo, allowPublicDigest);
        if (!demo) {
            applyReleaseScope(wrapper);
        }
        return reportService.list(wrapper);
    }

    private void applyOwnerScope(
            LambdaQueryWrapper<Report> wrapper, Long userId, boolean demo, boolean allowPublicDigest) {
        if (demo) {
            wrapper.eq(Report::getUserId, Report.PUBLIC_OWNER_ID);
            return;
        }
        if (userId == null) {
            wrapper.eq(Report::getId, -1L);
            return;
        }
        wrapper.and(w -> {
            w.eq(Report::getUserId, userId);
            if (allowPublicDigest) {
                w.or(or -> or.eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                        .likeRight(Report::getEdition, "market_watch"));
                w.or(or -> or.eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                        .in(Report::getEdition, "morning", "evening"));
            }
        });
    }

    private void applyReleaseScope(LambdaQueryWrapper<Report> wrapper) {
        LocalDateTime now = LocalDateTime.now(BEIJING);
        LocalDate today = now.toLocalDate();
        LocalTime minute = now.toLocalTime().withSecond(0).withNano(0);
        wrapper.and(w -> w
                .isNull(Report::getReportDate)
                .or()
                .lt(Report::getReportDate, today)
                .or(r -> r.eq(Report::getReportDate, today)
                        .and(t -> t.isNull(Report::getDisplayTime).or().le(Report::getDisplayTime, minute))));
    }

    private static Report releasedOrNull(Report report) {
        return ReportRelease.isReleased(report) ? report : null;
    }

    private void safeEnsureTodayAssembled(Long userId) {
        try {
            ensureTodayAssembled(userId);
        } catch (Exception e) {
            log.warn("拼装今日个人简报失败，不影响公共日报查询: userId={}, {}", userId, e.getMessage());
        }
    }

    private static boolean isPublicOnlyEdition(String edition) {
        return Report.isPublicDigest(edition) || Report.isSharedPublicEdition(edition);
    }

    private void assembleDueForToday(Long userId) {
        LocalDate today = LocalDate.now(BEIJING);
        LocalTime now = LocalTime.now(BEIJING).withSecond(0).withNano(0);
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        for (SubscriptionDTO.TopicScheduleItemDTO item : subscriptionPreferences.enabledTopicItemsOn(subscription, today)) {
            LocalTime time;
            try {
                time = ReportWindows.parse(item.getTime());
            } catch (RuntimeException e) {
                log.warn("跳过无效订阅时刻: userId={}, time={}", userId, item.getTime());
                continue;
            }
            if (time.isAfter(now)) continue;
            List<SubscriptionDTO.TopicScheduleItemDTO> slotItems =
                    subscriptionPreferences.enabledTopicItemsAt(subscription, time, today);
            reportAssemblyService.assembleForWebIfReadyItems(userId, today, time, slotItems);
            scheduledPushTask.catchUpUser(subscription, today, time);
        }
    }

    private Report latestPublicDigest() {
        return newest(
                releasedOrNull(reportService.getLatestByEdition("morning")),
                releasedOrNull(reportService.getLatestByEdition("evening")));
    }

    private static Report newest(Report left, Report right) {
        if (left == null) return right;
        if (right == null) return left;
        if (left.getCreatedAt() == null) return right;
        if (right.getCreatedAt() == null) return left;
        return left.getCreatedAt().isAfter(right.getCreatedAt()) ? left : right;
    }
}
