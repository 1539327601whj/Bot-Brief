package com.ai.daily.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MarketValuationIngestDTO {

    @NotBlank
    @Size(max = 32)
    private String indexCode;

    @NotBlank
    @Size(max = 100)
    private String indexName;

    @NotNull
    @DecimalMin(value = "0.0001")
    @DecimalMax(value = "300.0")
    private BigDecimal peTtm;

    @NotNull
    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    private BigDecimal pePercentile;

    /**
     * PB(MRQ)，可选。老数据源（蛋卷）没有这一列；缺失就是 NULL，不要填 0。
     */
    @DecimalMin(value = "0.0", inclusive = false)
    @DecimalMax(value = "300.0")
    private BigDecimal pb;

    /**
     * 股息率（百分数），可选。缺失就是 NULL。
     */
    @DecimalMin(value = "0.0")
    @DecimalMax(value = "50.0")
    private BigDecimal dividendYield;

    @NotBlank
    @Size(max = 64)
    private String percentileMethod;

    private String valuationLevel;

    @NotNull
    @PastOrPresent
    private LocalDate tradeDate;

    @Size(max = 100)
    private String source;
}
