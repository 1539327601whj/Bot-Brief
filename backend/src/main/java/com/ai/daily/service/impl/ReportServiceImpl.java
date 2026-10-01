package com.ai.daily.service.impl;

import com.ai.daily.entity.Report;
import com.ai.daily.mapper.ReportMapper;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.ReportWindows;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Report 服务实现
 */
@Slf4j
@Service
public class ReportServiceImpl extends ServiceImpl<ReportMapper, Report> implements ReportService {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+.+$");
    private static final Pattern MARKDOWN_DIVIDER = Pattern.compile("^[-*_—=\\s]+$");
    private static final Pattern SUBSTANTIVE_TEXT = Pattern.compile(".*[\\p{L}\\p{N}].*");

    @Override
    public boolean saveReport(LocalDate reportDate, String edition, String title, String content, String summary, String runId) {
        if (!hasSubstantiveContent(content)) {
            throw new IllegalArgumentException("简报缺少实质正文");
        }
        String ingestKey = runId != null && !runId.isBlank() && !"local".equals(runId)
                ? edition + ":" + runId
                : null;
        if (ingestKey != null && baseMapper.findIdByIngestKey(ingestKey) != null) {
            log.warn("公共简报重复入库，已跳过 edition={} date={} run_id={}", edition, reportDate, runId);
            return false;
        }
        Long existingId = baseMapper.findIdByEditionAndReportDate(edition, reportDate);
        if (existingId != null) {
            Report existing = baseMapper.selectById(existingId);
            if (existing == null) {
                log.debug("公共简报覆盖更新找不到原记录 edition={} date={} report_id={}", edition, reportDate, existingId);
                return false;
            }
            existing.setTitle(title);
            existing.setContent(content);
            existing.setSummary(summary);
            existing.setRunId(runId);
            existing.setIngestKey(ingestKey);
            existing.setDisplayTime(ReportWindows.publicDisplayTime(edition));
            existing.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
            boolean updated = baseMapper.updateById(existing) > 0;
            log.info("公共简报覆盖更新 edition={} date={} report_id={} run_id={} updated={}",
                    edition, reportDate, existing.getId(), runId, updated);
            return updated;
        }
        Report report = new Report();
        report.setUserId(Report.PUBLIC_OWNER_ID);
        report.setEdition(edition);
        report.setReportDate(reportDate);
        report.setDisplayTime(ReportWindows.publicDisplayTime(edition));
        report.setTitle(title);
        report.setContent(content);
        report.setSummary(summary);
        report.setRunId(runId);
        report.setIngestKey(ingestKey);
        report.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        try {
            if (!this.save(report)) {
                throw new IllegalStateException("简报保存失败");
            }
            log.info("公共简报入库 edition={} date={} report_id={} run_id={}", edition, reportDate, report.getId(), runId);
            return true;
        } catch (DuplicateKeyException e) {
            boolean duplicateRun = ingestKey != null && baseMapper.findIdByIngestKey(ingestKey) != null;
            boolean duplicateBusinessReport = baseMapper.findIdByEditionAndReportDate(edition, reportDate) != null;
            if (!duplicateRun && !duplicateBusinessReport) {
                throw e;
            }
            log.warn("公共简报重复入库，已去重 edition={} date={} run_id={} duplicate_run={} duplicate_business={}",
                    edition, reportDate, runId, duplicateRun, duplicateBusinessReport);
            return false;
        }
    }

    public static boolean hasSubstantiveContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        for (String line : content.replace("﻿", "").split("\\R")) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith(">") || MARKDOWN_DIVIDER.matcher(value).matches()) {
                continue;
            }
            String normalized = value;
            normalized = normalized.replace("**", "").replace("__", "").strip();
            if (MARKDOWN_HEADING.matcher(normalized).matches()) {
                continue;
            }
            if (SUBSTANTIVE_TEXT.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Report saveUserReport(Long userId, LocalDate reportDate, LocalTime displayTime, String title, String content, String summary) {
        if (userId == null || userId == Report.PUBLIC_OWNER_ID) {
            throw new IllegalArgumentException("用户简报必须指定用户");
        }
        if (displayTime == null) {
            throw new IllegalArgumentException("展示时间不能为空");
        }
        if (!hasSubstantiveContent(content)) {
            throw new IllegalArgumentException("简报缺少实质正文");
        }
        LocalTime minute = displayTime.withSecond(0).withNano(0);
        Long existingId = baseMapper.findIdByUserEditionDateAndTime(userId, Report.PERSONAL, reportDate, minute);
        if (existingId != null) {
            log.debug("用户简报已存在，直接返回 user={} date={} time={} report_id={}", userId, reportDate, minute, existingId);
            return this.getById(existingId);
        }
        Report report = new Report();
        report.setUserId(userId);
        report.setEdition(Report.PERSONAL);
        report.setReportDate(reportDate);
        report.setDisplayTime(minute);
        report.setTitle(title);
        report.setContent(content);
        report.setSummary(summary);
        report.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        try {
            if (!this.save(report)) {
                throw new IllegalStateException("用户简报保存失败");
            }
            log.info("用户简报入库 user={} date={} time={} report_id={}", userId, reportDate, minute, report.getId());
            return report;
        } catch (DuplicateKeyException e) {
            Long raced = baseMapper.findIdByUserEditionDateAndTime(userId, Report.PERSONAL, reportDate, minute);
            if (raced != null) {
                log.debug("用户简报并发写入，返回已存在记录 user={} date={} time={} report_id={}",
                        userId, reportDate, minute, raced);
                return this.getById(raced);
            }
            throw e;
        }
    }

    @Override
    public Report getLatestReport() {
        return this.lambdaQuery()
                .eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getLatestByEdition(String edition) {
        return this.lambdaQuery()
                .eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                .eq(Report::getEdition, edition)
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getLatestByEditionForDate(String edition, LocalDate date) {
        Report report = this.lambdaQuery()
                .eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                .eq(Report::getEdition, edition)
                .eq(Report::getReportDate, date)
                .last("LIMIT 1")
                .one();
        if (report != null) {
            return report;
        }
        LocalDateTime start = date.atStartOfDay();
        return this.lambdaQuery()
                .eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                .eq(Report::getEdition, edition)
                .isNull(Report::getReportDate)
                .ge(Report::getCreatedAt, start)
                .lt(Report::getCreatedAt, date.plusDays(1).atStartOfDay())
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getByUserEditionDate(Long userId, String edition, LocalDate date) {
        if (userId == null || edition == null || date == null) return null;
        return this.lambdaQuery()
                .eq(Report::getUserId, userId)
                .eq(Report::getEdition, edition)
                .eq(Report::getReportDate, date)
                .orderByDesc(Report::getDisplayTime)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getByUserEditionDateAndTime(Long userId, String edition, LocalDate date, LocalTime displayTime) {
        if (userId == null || edition == null || date == null || displayTime == null) return null;
        return this.lambdaQuery()
                .eq(Report::getUserId, userId)
                .eq(Report::getEdition, edition)
                .eq(Report::getReportDate, date)
                .eq(Report::getDisplayTime, displayTime.withSecond(0).withNano(0))
                .last("LIMIT 1")
                .one();
    }

    @Override
    public List<Report> listForUserOnDate(Long userId, LocalDate date) {
        if (userId == null || date == null) return List.of();
        return this.lambdaQuery()
                .eq(Report::getUserId, userId)
                .eq(Report::getEdition, Report.PERSONAL)
                .eq(Report::getReportDate, date)
                .orderByAsc(Report::getDisplayTime)
                .list();
    }

    @Override
    public Report getLatestForUser(Long userId, String edition) {
        if (userId == null) return null;
        return this.lambdaQuery()
                .eq(Report::getUserId, userId)
                .eq(edition != null && !edition.isBlank(), Report::getEdition, edition)
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getLatestPublicMarketWatch() {
        return this.lambdaQuery()
                .eq(Report::getUserId, Report.PUBLIC_OWNER_ID)
                .likeRight(Report::getEdition, "market_watch")
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public boolean publicReportExists(String edition, LocalDate date) {
        return date != null && edition != null && baseMapper.findIdByEditionAndReportDate(edition, date) != null;
    }
}