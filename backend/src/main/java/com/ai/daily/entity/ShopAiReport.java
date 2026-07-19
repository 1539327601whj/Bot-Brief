package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("shop_ai_report")
public class ShopAiReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long storeId;

    private LocalDate reportDate;

    private String title;

    private String summary;

    private String content;

    private String riskLevel;

    private String generatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
