package com.ai.daily.controller;

import com.ai.daily.dto.ReportPushDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.Report;
import com.ai.daily.service.ReportService;
import com.ai.daily.util.MarkdownUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简报控制器
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final int SUMMARY_MAX_LEN = 80;

    @Autowired
    private ReportService reportService;

    @Value("${report.ingest-token:}")
    private String ingestToken;

    /**
     * GitHub Actions 推送简报（阶段 3 起走这个新路径 + X-Ingest-Token 校验）
     * POST /api/reports/ingest
     */
    @PostMapping("/ingest")
    public Result<String> ingest(@Valid @RequestBody ReportPushDTO dto, HttpServletRequest req) {
        String provided = req.getHeader("X-Ingest-Token");
        if (ingestToken == null || ingestToken.isBlank())
            return Result.error(500, "服务端未配置 REPORT_INGEST_TOKEN");
        if (provided == null || !ingestToken.equals(provided))
            return Result.error(401, "无效的 X-Ingest-Token");
        return doSave(dto);
    }

    /**
     * 保留旧路径 POST /api/reports 以做向后兼容（阶段 3 结束前可下线）
     */
    @PostMapping
    public Result<String> pushReport(@Valid @RequestBody ReportPushDTO dto) {
        return doSave(dto);
    }

    private Result<String> doSave(ReportPushDTO dto) {
        String summary = dto.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = MarkdownUtils.stripToPlainText(dto.getContent(), SUMMARY_MAX_LEN);
        }
        reportService.saveReport(
                dto.getEdition(),
                dto.getTitle(),
                dto.getContent(),
                summary,
                dto.getRunId()
        );
        return Result.ok("简报已保存", null);
    }

    /**
     * 一次性回刷:用剥离 markdown 后的纯文本重写所有 summary
     * POST /api/reports/refresh-summaries
     */
    @PostMapping("/refresh-summaries")
    public Result<Map<String, Object>> refreshSummaries() {
        List<Report> all = reportService.list();
        int updated = 0;
        for (Report r : all) {
            String next = MarkdownUtils.stripToPlainText(r.getContent(), SUMMARY_MAX_LEN);
            if (next != null && !next.equals(r.getSummary())) {
                r.setSummary(next);
                reportService.updateById(r);
                updated++;
            }
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", all.size());
        data.put("updated", updated);
        return Result.ok(data);
    }

    /**
     * 获取简报列表（分页 + 过滤）
     * GET /api/reports?page=1&size=10&edition=morning&startDate=2026-06-01&endDate=2026-07-01&keyword=Claude
     */
    @GetMapping
    public Result<Map<String, Object>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String edition,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword) {

        Page<Report> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(edition != null && !edition.isBlank(), Report::getEdition, edition);

        if (startDate != null && !startDate.isBlank()) {
            wrapper.ge(Report::getCreatedAt, java.time.LocalDate.parse(startDate).atStartOfDay());
        }
        if (endDate != null && !endDate.isBlank()) {
            wrapper.lt(Report::getCreatedAt, java.time.LocalDate.parse(endDate).plusDays(1).atStartOfDay());
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Report::getTitle, kw).or().like(Report::getSummary, kw));
        }

        wrapper.orderByDesc(Report::getCreatedAt);

        Page<Report> result = reportService.page(pageObj, wrapper);

        Map<String, Object> data = new HashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("pages", result.getPages());
        data.put("current", result.getCurrent());
        data.put("size", result.getSize());

        return Result.ok(data);
    }

    /**
     * 获取最新简报
     * GET /api/reports/latest
     */
    @GetMapping("/latest")
    public Result<Report> getLatest(
            @RequestParam(required = false) String edition) {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(edition != null, Report::getEdition, edition)
               .orderByDesc(Report::getCreatedAt)
               .last("LIMIT 1");
        Report report = reportService.getOne(wrapper);
        if (report == null) {
            return Result.error(404, "暂无简报");
        }
        return Result.ok(report);
    }

    /**
     * 获取单条简报详情
     * GET /api/reports/{id}
     */
    @GetMapping("/{id}")
    public Result<Report> getById(@PathVariable Long id) {
        Report report = reportService.getById(id);
        if (report == null) {
            return Result.error(404, "简报不存在");
        }
        return Result.ok(report);
    }
}
