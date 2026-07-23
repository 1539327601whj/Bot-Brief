package com.ai.daily.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class MarketValuationIngestDTO {

    @NotBlank
    private String indexCode;

    @NotBlank
    private String indexName;

    @NotNull
    @DecimalMin(value = "0.0001")
    private BigDecimal peTtm;

    @NotNull
    @DecimalMin(value = "0.0")
    @DecimalMax(value = "100.0")
    private BigDecimal pePercentile;

    private String valuationLevel;

    @NotNull
    private LocalDate tradeDate;

    private String source;
}
