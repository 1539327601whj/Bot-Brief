package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.ShopAiReportDTO;
import com.ai.daily.dto.ShopOverviewDTO;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ShopAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
    public Result<String> generateDemoData(@RequestParam(required = false) Long storeId) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            shopAnalyticsService.generateDemoData(userId, storeId);
            return Result.ok("模拟数据已生成", null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping("/ai-report/generate")
    public Result<ShopAiReportDTO> generateAiReport(@RequestParam(required = false) Long storeId) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok("经营日报已生成", shopAnalyticsService.generateAiReport(userId, storeId));
        } catch (IllegalArgumentException e) {
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
}
