package com.ai.daily.service.impl;

import com.ai.daily.entity.Report;
import com.ai.daily.mapper.ReportMapper;
import com.ai.daily.service.ReportService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.regex.Pattern;

/**
 * Report 服务实现
 */
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
            return false;
        }
        if (baseMapper.findIdByEditionAndReportDate(edition, reportDate) != null) {
            return false;
        }
        Report report = new Report();
        report.setEdition(edition);
        report.setReportDate(reportDate);
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
            return true;
        } catch (DuplicateKeyException e) {
            boolean duplicateRun = ingestKey != null && baseMapper.findIdByIngestKey(ingestKey) != null;
            boolean duplicateBusinessReport = baseMapper.findIdByEditionAndReportDate(edition, reportDate) != null;
            if (!duplicateRun && !duplicateBusinessReport) {
                throw e;
            }
            return false;
        }
    }

    static boolean hasSubstantiveContent(String content) {
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
    public Report getLatestReport() {
        return this.lambdaQuery()
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getLatestByEdition(String edition) {
        return this.lambdaQuery()
                .eq(Report::getEdition, edition)
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public Report getLatestByEditionForDate(String edition, LocalDate date) {
        Report report = this.lambdaQuery()
                .eq(Report::getEdition, edition)
                .eq(Report::getReportDate, date)
                .last("LIMIT 1")
                .one();
        if (report != null) {
            return report;
        }
        LocalDateTime start = date.atStartOfDay();
        return this.lambdaQuery()
                .eq(Report::getEdition, edition)
                .isNull(Report::getReportDate)
                .ge(Report::getCreatedAt, start)
                .lt(Report::getCreatedAt, date.plusDays(1).atStartOfDay())
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }
}