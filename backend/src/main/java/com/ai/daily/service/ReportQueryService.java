package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
        LocalDate today = LocalDate.now(BEIJING);
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        assembleIfReady(userId, "morning", today, subscription);
        assembleIfReady(userId, "evening", today, subscription);
    }

    public Report getLatest(Long userId, boolean demo, String edition) {
        if (demo) {
            if (edition == null || edition.isBlank()) {
                return reportService.getLatestReport();
            }
            return reportService.getLatestByEdition(edition);
        }
        if (userId == null) return null;
        ensureTodayAssembled(userId, edition);
        if (edition != null && !edition.isBlank()) {
            if (Report.isPersonalizedEdition(edition)) {
                return reportService.getLatestForUser(userId, edition);
            }
            if (Report.isSharedPublicEdition(edition)) {
                return reportService.getLatestByEdition(edition);
            }
            return null;
        }
        Report mine = reportService.getLatestForUser(userId, null);
        Report market = reportService.getLatestPublicMarketWatch();
        if (mine == null) return market;
        if (market == null) return mine;
        if (mine.getCreatedAt() == null) return market;
        if (market.getCreatedAt() == null) return mine;
        return mine.getCreatedAt().isAfter(market.getCreatedAt()) ? mine : market;
    }

    public Report getById(Long userId, boolean demo, Long id) {
        Report report = reportService.getById(id);
        if (report == null) return null;
        if (demo) {
            return Report.isPublicOwner(report.getUserId()) ? report : null;
        }
        if (userId != null && userId.equals(report.getUserId())) return report;
        return Report.isPublicOwner(report.getUserId()) && Report.isSharedPublicEdition(report.getEdition())
                ? report
                : null;
    }

    public Page<Report> pageVisible(
            Long userId,
            boolean demo,
            Page<Report> page,
            String edition,
            LocalDateTime start,
            LocalDateTime end,
            String keyword) {
        if (!demo && userId != null) {
            ensureTodayAssembled(userId, edition);
        }
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (demo) {
            wrapper.eq(Report::getUserId, Report.PUBLIC_OWNER_ID);
            wrapper.eq(edition != null && !edition.isBlank(), Report::getEdition, edition);
        } else if (userId == null) {
            return page;
        } else if (edition != null && !edition.isBlank()) {
            if (Report.isPersonalizedEdition(edition)) {
                wrapper.eq(Report::getUserId, userId).eq(Report::getEdition, edition);
            } else if (Report.isSharedPublicEdition(edition)) {
                wrapper.eq(Report::getUserId, Report.PUBLIC_OWNER_ID).eq(Report::getEdition, edition);
            } else {
                return page;
            }
        } else {
            wrapper.and(w -> w.eq(Report::getUserId, userId)
                    .or(or -> or.eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                            .likeRight(Report::getEdition, "market_watch")));
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
        if (!demo && userId != null) {
            ensureTodayAssembled(userId);
        }
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        if (start != null) wrapper.ge(Report::getCreatedAt, start);
        if (end != null) wrapper.lt(Report::getCreatedAt, end);
        applyOwnerScope(wrapper, userId, demo);
        return reportService.count(wrapper);
    }

    public List<Report> listVisibleForStats(Long userId, boolean demo, LocalDateTime start) {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .ge(start != null, Report::getCreatedAt, start)
                .select(Report::getTitle, Report::getSummary);
        applyOwnerScope(wrapper, userId, demo);
        return reportService.list(wrapper);
    }

    private void applyOwnerScope(LambdaQueryWrapper<Report> wrapper, Long userId, boolean demo) {
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
                        .likeRight(Report::getEdition, "market_watch")));
    }

    private void ensureTodayAssembled(Long userId, String edition) {
        LocalDate today = LocalDate.now(BEIJING);
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        if (edition == null || edition.isBlank()) {
            assembleIfReady(userId, "morning", today, subscription);
            assembleIfReady(userId, "evening", today, subscription);
            return;
        }
        if (Report.isPersonalizedEdition(edition)) {
            assembleIfReady(userId, edition, today, subscription);
        }
    }

    private void assembleIfReady(Long userId, String edition, LocalDate date, Subscription subscription) {
        List<String> topics = subscriptionPreferences.enabledTopics(subscription, edition);
        reportAssemblyService.assembleForWebIfReady(userId, edition, date, topics);
    }
}
