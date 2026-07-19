package com.ai.daily.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class ShopSalesTrendDTO {
    private LocalDate date;
    private BigDecimal salesAmount;
    private Integer orderCount;
    private Integer buyerCount;
}
