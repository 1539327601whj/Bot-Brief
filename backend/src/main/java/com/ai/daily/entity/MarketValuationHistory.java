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

    /** PB(MRQ)。V15 新增，可空——历史行与只提供 PE 的源不会有值。 */
    private BigDecimal pb;

    /** 股息率（百分数，例如 2.35 表示 2.35%）。V15 新增，可空。 */
    private BigDecimal dividendYield;

    private String percentileMethod;

    private String valuationLevel;

    private LocalDate tradeDate;

    private String source;

    private LocalDateTime createdAt;
}
