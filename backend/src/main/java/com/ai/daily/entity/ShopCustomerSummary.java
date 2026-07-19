package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("shop_customer_summary")
public class ShopCustomerSummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long storeId;

    private LocalDate statDate;

    private Integer newCustomerCount;

    private Integer repeatCustomerCount;

    private Integer highValueCustomerCount;

    private BigDecimal avgCustomerValue;

    private BigDecimal femaleRatio;

    private BigDecimal maleRatio;

    private String topRegions;

    private String ageDistribution;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
