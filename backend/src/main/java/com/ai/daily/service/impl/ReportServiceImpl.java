package com.ai.daily.service.impl;

import com.ai.daily.entity.Report;
import com.ai.daily.mapper.ReportMapper;
import com.ai.daily.service.ReportService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
        report.setCreatedAt(LocalDateTime.now());
        this.save(report);
    }

    @Override
    public Report getLatestReport() {
        return this.lambdaQuery()
                .orderByDesc(Report::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }
}