package com.ai.daily.service.impl;

import com.ai.daily.dto.MarketValuationIngestDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketValuationHistoryServiceImplTest {

    private final MarketValuationHistoryServiceImpl service = new MarketValuationHistoryServiceImpl();

    @Test
    void acceptsValuationWithExplicitPercentileMethod() {
        assertThatCode(() -> service.validate(validValuation())).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingPercentileMethod() {
        MarketValuationIngestDTO dto = validValuation();
        dto.setPercentileMethod(" ");

        assertThatThrownBy(() -> service.validate(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percentileMethod");
    }

    @Test
    void rejectsUnsupportedPercentileMethod() {
        MarketValuationIngestDTO dto = validValuation();
        dto.setPercentileMethod("LEGACY_UNKNOWN");

        assertThatThrownBy(() -> service.validate(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不受支持");
    }

    @Test
    void rejectsUnreasonablePeAndFutureDate() {
        MarketValuationIngestDTO excessivePe = validValuation();
        excessivePe.setPeTtm(new BigDecimal("300.0001"));
        assertThatThrownBy(() -> service.validate(excessivePe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("300");

        MarketValuationIngestDTO futureValuation = validValuation();
        futureValuation.setTradeDate(LocalDate.now().plusDays(1));
        assertThatThrownBy(() -> service.validate(futureValuation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tradeDate");
    }

    private MarketValuationIngestDTO validValuation() {
        MarketValuationIngestDTO dto = new MarketValuationIngestDTO();
        dto.setIndexCode("000300");
        dto.setIndexName("沪深300");
        dto.setPeTtm(new BigDecimal("12.34"));
        dto.setPePercentile(new BigDecimal("45.6"));
        dto.setPercentileMethod("CSI_PE_TTM_ROLLING_10Y");
        dto.setTradeDate(LocalDate.now());
        dto.setSource("中证指数官网PE(TTM)，滚动10年分位");
        return dto;
    }
}
