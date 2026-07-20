package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("market_valuation_history")
public class MarketValuationHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String indexCode;

    private String indexName;

    private BigDecimal peTtm;

    private BigDecimal pePercentile;

    private String valuationLevel;

    private LocalDate tradeDate;

    private String source;

    private LocalDateTime createdAt;
}
