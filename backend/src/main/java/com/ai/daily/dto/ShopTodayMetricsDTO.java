package com.ai.daily.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ShopTodayMetricsDTO {
    private BigDecimal salesAmount;
    private Integer orderCount;
    private Integer buyerCount;
    private BigDecimal averageOrderValue;
    private BigDecimal salesChangeRate;
    private BigDecimal orderChangeRate;
    private Integer repeatCustomerCount;
}
