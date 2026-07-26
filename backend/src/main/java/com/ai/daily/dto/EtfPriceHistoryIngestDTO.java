package com.ai.daily.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class EtfPriceHistoryIngestDTO {

    @NotBlank
    @Size(max = 32)
    private String fundCode;

    @NotBlank
    @Size(max = 100)
    private String fundName;

    @NotNull
    @PastOrPresent
    private LocalDate tradeDate;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal open;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal high;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal low;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal close;

    @NotBlank
    @Size(max = 16)
    private String adjustmentType;

    @NotBlank
    @Size(max = 100)
    private String source;

    @NotNull
    private LocalDateTime fetchedAt;
}
