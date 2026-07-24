package com.ai.daily.service.impl;

import com.ai.daily.entity.Report;
import com.ai.daily.mapper.ReportMapper;
import com.ai.daily.service.ReportService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Report 服务实现
 */
@Service
public class ReportServiceImpl extends ServiceImpl<ReportMapper, Report> implements ReportService {

    @Override
    public void saveReport(String edition, String title, String content, String summary, String runId) {
        Report report = new Report();
        report.setEdition(edition);
        report.setTitle(title);
        report.setContent(content);
        report.setSummary(summary);
        report.setRunId(runId);
        // 使用北京时间存储
        report.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        this.save(report);
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
        LocalDateTime start = date.atStartOfDay();
        return this.lambdaQuery()
                .eq(Report::getEdition, edition)
                .ge(Report::getCreatedAt, start)
                .lt(Report::getCreatedAt, date.plusDays(1).atStartOfDay())
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }
}