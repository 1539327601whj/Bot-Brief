package com.ai.daily.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ShopReplenishmentSuggestionDTO {
    private Long productId;
    private String productName;
    private Integer stock;
    private BigDecimal avgDailySales;
    private BigDecimal estimatedDaysLeft;
    private Integer suggestedReplenishment;
    private String priority;
    private String reason;
}
