package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("shop_sales_daily")
public class ShopSalesDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long storeId;

    private LocalDate statDate;

    private BigDecimal salesAmount;

    private Integer orderCount;

    private Integer buyerCount;

    private BigDecimal refundAmount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
