package com.ai.daily.controller;

import com.ai.daily.dto.ReportPushDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.Report;
import com.ai.daily.service.ReportService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 简报控制器
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired
    private ReportService reportService;

    /**
     * GitHub Actions 推送简报
     * POST /api/reports
     */
    @PostMapping
    public Result<String> pushReport(@Valid @RequestBody ReportPushDTO dto) {
        String summary = dto.getSummary();
        if (summary == null || summary.isBlank()) {
            // 自动截取前 100 字作为摘要
            summary = dto.getContent().length() > 100
                    ? dto.getContent().substring(0, 100) + "..."
                    : dto.getContent();
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
     * 获取简报列表（分页）
     * GET /api/reports?page=1&size=10
     */
    @GetMapping
    public Result<Map<String, Object>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String edition) {

        Page<Report> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(edition != null, Report::getEdition, edition)
               .orderByDesc(Report::getCreatedAt);

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
