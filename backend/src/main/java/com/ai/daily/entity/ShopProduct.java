package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("shop_product")
public class ShopProduct {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long storeId;

    private String platform;

    private String externalProductId;

    private String productName;

    private String category;

    private BigDecimal price;

    private Integer stock;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
