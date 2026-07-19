package com.ai.daily.dto;

import lombok.Data;

import java.util.List;

@Data
public class ShopOverviewDTO {
    private ShopTodayMetricsDTO today;
    private List<ShopProductRankDTO> hotProducts;
    private List<ShopProductRankDTO> slowProducts;
    private ShopCustomerProfileDTO customers;
    private List<ShopSalesTrendDTO> salesTrend;
    private List<ShopReplenishmentSuggestionDTO> replenishmentSuggestions;
    private List<ShopActivitySuggestionDTO> activitySuggestions;
    private ShopAiReportDTO aiReport;
}
