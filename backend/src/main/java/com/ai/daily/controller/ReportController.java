package com.ai.daily.controller;

import com.ai.daily.dto.ReportPushDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.dto.TopicSectionPushDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ReportQueryService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.SubscribedTopicService;
import com.ai.daily.service.TopicSectionService;
import com.ai.daily.task.ScheduledPushTask;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简报控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired
    private ReportService reportService;

    @Autowired
    private ReportQueryService reportQueryService;

    @Autowired
    private TopicSectionService topicSectionService;

    @Autowired
    private SubscribedTopicService subscribedTopicService;

    @Autowired
    private ScheduledPushTask scheduledPushTask;

    @Value("${report.ingest-token:}")
    private String ingestToken;

    @PostMapping("/ingest")
    public Result<Boolean> ingestReport(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @Valid @RequestBody ReportPushDTO dto) {
        if (invalidIngestToken(token)) {
            return Result.error(401, "入库 token 无效");
        }
        return saveReport(dto);
    }

    @GetMapping("/subscribed-topics")
    public Result<Map<String, List<String>>> subscribedTopics(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @RequestParam String edition) {
        if (invalidIngestToken(token)) {
            return Result.error(401, "入库 token 无效");
        }
        if (!Report.isPersonalizedEdition(edition)) {
            return Result.error(400, "edition 只支持 morning 或 evening");
        }
        return Result.ok(Map.of("topics", subscribedTopicService.listTopics(edition)));
    }

    @PostMapping("/sections/ingest")
    public Result<Boolean> ingestTopicSection(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @Valid @RequestBody TopicSectionPushDTO dto) {
        if (invalidIngestToken(token)) {
            return Result.error(401, "入库 token 无效");
        }
        String summary = dto.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = dto.getContent().length() > 100
                    ? dto.getContent().substring(0, 100) + "..."
                    : dto.getContent();
        }
        try {
            boolean created = topicSectionService.saveSection(
                    dto.getReportDate(),
                    dto.getEdition(),
                    dto.getTopic(),
                    dto.getTitle(),
                    dto.getContent(),
                    summary,
                    dto.getRunId()
            );
            return Result.ok(created ? "主题段落已保存" : "主题段落已存在", created);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    private Result<Boolean> saveReport(ReportPushDTO dto) {
        String summary = dto.getSummary();
        if (summary == null || summary.isBlank()) {
            summary = dto.getContent().length() > 100
                    ? dto.getContent().substring(0, 100) + "..."
                    : dto.getContent();
        }
        try {
            boolean created = reportService.saveReport(
                    dto.getReportDate(),
                    dto.getEdition(),
                    dto.getTitle(),
                    dto.getContent(),
                    summary,
                    dto.getRunId()
            );
            try {
                scheduledPushTask.catchUpEdition(dto.getEdition(), dto.getReportDate());
            } catch (Exception e) {
                log.warn("简报入库后补推失败 edition={} date={}", dto.getEdition(), dto.getReportDate(), e);
            }
            return Result.ok(created ? "简报已保存" : "简报已存在", created);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /**
     * 获取简报列表（分页）
     * GET /api/reports?page=1&size=10
     */
    @GetMapping
    public Result<Map<String, Object>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String edition,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword) {
        if (page < 1 || size < 1 || size > 50) {
            return Result.error(400, "分页参数无效：page 必须大于等于 1，size 必须在 1 到 50 之间");
        }

        Long userId = SecurityUtils.currentUserId();
        boolean demo = SecurityUtils.isDemo();
        LocalDateTime start = parseStart(startDate);
        LocalDateTime end = parseEnd(endDate);
        if (startDate != null && !startDate.isBlank() && start == null) {
            return Result.error(400, "开始日期无效");
        }
        if (endDate != null && !endDate.isBlank() && end == null) {
            return Result.error(400, "结束日期无效");
        }

        Page<Report> result = reportQueryService.pageVisible(
                userId, demo, new Page<>(page, size), edition, start, end, keyword);

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
        Report report = reportQueryService.getLatest(SecurityUtils.currentUserId(), SecurityUtils.isDemo(), edition);
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
        Report report = reportQueryService.getById(SecurityUtils.currentUserId(), SecurityUtils.isDemo(), id);
        if (report == null) {
            return Result.error(404, "简报不存在");
        }
        return Result.ok(report);
    }

    private boolean invalidIngestToken(String token) {
        return ingestToken == null || ingestToken.isBlank() || !ingestToken.equals(token);
    }

    private LocalDateTime parseStart(String startDate) {
        if (startDate == null || startDate.isBlank()) return null;
        try {
            return LocalDateTime.of(LocalDate.parse(startDate), LocalTime.MIN);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseEnd(String endDate) {
        if (endDate == null || endDate.isBlank()) return null;
        try {
            return LocalDateTime.of(LocalDate.parse(endDate), LocalTime.MAX);
        } catch (Exception e) {
            return null;
        }
    }
}
