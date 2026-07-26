package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("etf_price_history")
public class EtfPriceHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fundCode;

    private String fundName;

    private LocalDate tradeDate;

    @TableField("open_price")
    private BigDecimal open;

    @TableField("high_price")
    private BigDecimal high;

    @TableField("low_price")
    private BigDecimal low;

    @TableField("close_price")
    private BigDecimal close;

    private String adjustmentType;

    private String source;

    private LocalDateTime fetchedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
