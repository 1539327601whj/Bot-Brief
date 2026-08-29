package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReportQueryService {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final ReportService reportService;
    private final ReportAssemblyService reportAssemblyService;
    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;

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
        if (userId == null) return null;
        ensureTodayAssembled(userId);
        if (edition != null && !edition.isBlank()) {
            if (Report.isPersonalizedEdition(edition)) {
                return reportService.getLatestForUser(userId, Report.PERSONAL);
            }
            if (Report.isSharedPublicEdition(edition)) {
                return reportService.getLatestByEdition(edition);
            }
            if (allowPublicDigest && Report.isPublicDigest(edition)) {
                return reportService.getLatestByEdition(edition);
            }
            return null;
        }
        Report mine = reportService.getLatestForUser(userId, Report.PERSONAL);
        Report market = reportService.getLatestPublicMarketWatch();
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
        if (userId != null && userId.equals(report.getUserId())) return report;
        if (!Report.isPublicOwner(report.getUserId())) return null;
        if (Report.isSharedPublicEdition(report.getEdition())) return report;
        return allowPublicDigest && Report.isPublicDigest(report.getEdition()) ? report : null;
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
        if (!demo && userId != null) {
            ensureTodayAssembled(userId);
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
            } else if (Report.isSharedPublicEdition(edition)) {
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
        wrapper.orderByDesc(Report::getCreatedAt);
        return reportService.page(page, wrapper);
    }

    public long countVisible(Long userId, boolean demo, LocalDateTime start, LocalDateTime end) {
        return countVisible(userId, demo, false, start, end);
    }

    public long countVisible(
            Long userId, boolean demo, boolean allowPublicDigest, LocalDateTime start, LocalDateTime end) {
        if (!demo && userId != null) {
            ensureTodayAssembled(userId);
        }
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (start != null) wrapper.ge(Report::getCreatedAt, start);
        if (end != null) wrapper.lt(Report::getCreatedAt, end);
        applyOwnerScope(wrapper, userId, demo, allowPublicDigest);
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
        wrapper.and(w -> w.eq(Report::getUserId, userId)
                .or(or -> or.eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                        .and(publicReport -> {
                            if (allowPublicDigest) {
                                publicReport.likeRight(Report::getEdition, "market_watch")
                                        .or()
                                        .in(Report::getEdition, "morning", "evening");
                            } else {
                                publicReport.likeRight(Report::getEdition, "market_watch");
                            }
                        })));
    }

    private void assembleDueForToday(Long userId) {
        LocalDate today = LocalDate.now(BEIJING);
        LocalTime now = LocalTime.now(BEIJING).withSecond(0).withNano(0);
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        for (SubscriptionDTO.TopicScheduleItemDTO item : subscriptionPreferences.enabledTopicItemsOn(subscription, today)) {
            LocalTime time = ReportWindows.parse(item.getTime());
            if (time.isAfter(now)) continue;
            List<String> topics = subscriptionPreferences.enabledTopicItemsAt(subscription, time, today).stream()
                    .map(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                    .toList();
            reportAssemblyService.assembleForWebIfReady(userId, today, time, topics);
        }
    }

    private Report latestPublicDigest() {
        return newest(reportService.getLatestByEdition("morning"), reportService.getLatestByEdition("evening"));
    }

    private static Report newest(Report left, Report right) {
        if (left == null) return right;
        if (right == null) return left;
        if (left.getCreatedAt() == null) return right;
        if (right.getCreatedAt() == null) return left;
        return left.getCreatedAt().isAfter(right.getCreatedAt()) ? left : right;
    }
}
