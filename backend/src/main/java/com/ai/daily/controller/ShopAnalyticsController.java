package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.ShopAiReportDTO;
import com.ai.daily.dto.ShopOverviewDTO;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ShopAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/shop/analytics")
@RequiredArgsConstructor
public class ShopAnalyticsController {

    private final ShopAnalyticsService shopAnalyticsService;

    @GetMapping("/overview")
    public Result<ShopOverviewDTO> getOverview(@RequestParam(required = false) Long storeId,
                                               @RequestParam(defaultValue = "7") int range) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(shopAnalyticsService.getOverview(userId, storeId, range));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping("/demo-data")
    public Result<String> generateDemoData(@RequestParam(required = false) Long storeId,
                                           @RequestParam(defaultValue = "false") boolean overwrite) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            shopAnalyticsService.generateDemoData(userId, storeId, overwrite);
            log.info("生成店铺模拟数据 user={} storeId={} overwrite={}", userId, storeId, overwrite);
            return Result.ok("模拟数据已生成", null);
        } catch (IllegalArgumentException e) {
            log.warn("生成店铺模拟数据参数非法 user={} storeId={} reason={}", userId, storeId, e.getMessage());
            return Result.error("店铺不存在".equals(e.getMessage()) ? 404 : 400, e.getMessage());
        }
    }

    @PostMapping("/ai-report/generate")
    public Result<ShopAiReportDTO> generateAiReport(@RequestParam(required = false) Long storeId) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            ShopAiReportDTO report = shopAnalyticsService.generateAiReport(userId, storeId);
            log.info("生成店铺经营日报 user={} storeId={}", userId, storeId);
            return Result.ok("经营日报已生成", report);
        } catch (IllegalArgumentException e) {
            log.warn("生成店铺经营日报参数非法 user={} storeId={} reason={}", userId, storeId, e.getMessage());
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/ai-report/latest")
    public Result<ShopAiReportDTO> getLatestAiReport(@RequestParam(required = false) Long storeId) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(shopAnalyticsService.getLatestAiReport(userId, storeId));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/ai-report/history")
    public Result<Map<String, Object>> getAiReportHistory(@RequestParam Long storeId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "10") int size) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(shopAnalyticsService.getAiReportHistory(userId, storeId, page, size));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/ai-report/{id}")
    public Result<ShopAiReportDTO> getAiReport(@PathVariable Long id, @RequestParam Long storeId) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(shopAnalyticsService.getAiReport(userId, storeId, id));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
