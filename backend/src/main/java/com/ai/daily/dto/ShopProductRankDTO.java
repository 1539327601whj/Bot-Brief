package com.ai.daily.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ShopProductRankDTO {
    private Long productId;
    private String productName;
    private BigDecimal salesAmount;
    private Integer orderCount;
    private Integer quantitySold;
    private Integer stock;
    private String trend;
    private BigDecimal trendRate;
    private Integer dataDays;
    private BigDecimal avgDailySales;
    private String reason;
}
