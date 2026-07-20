package com.ai.daily.dto;

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

    private BigDecimal peTtm;

    private BigDecimal pePercentile;

    private String valuationLevel;

    @NotNull
    private LocalDate tradeDate;

    private String source;
}
