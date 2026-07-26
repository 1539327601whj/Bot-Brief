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
