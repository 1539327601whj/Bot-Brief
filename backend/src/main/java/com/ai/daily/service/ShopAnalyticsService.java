package com.ai.daily.service;

import com.ai.daily.dto.ShopAiReportDTO;
import com.ai.daily.dto.ShopOverviewDTO;

public interface ShopAnalyticsService {
    void generateDemoData(Long userId, Long storeId);

    ShopOverviewDTO getOverview(Long userId, Long storeId, int range);

    ShopAiReportDTO generateAiReport(Long userId, Long storeId);

    ShopAiReportDTO getLatestAiReport(Long userId, Long storeId);
}
