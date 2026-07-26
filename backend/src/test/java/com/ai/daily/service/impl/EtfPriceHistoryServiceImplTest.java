package com.ai.daily.service.impl;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.mapper.EtfPriceHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EtfPriceHistoryServiceImplTest {

    private final EtfPriceHistoryServiceImpl service = new EtfPriceHistoryServiceImpl();

    @Test
    void acceptsValidQfqOhlcRecord() {
        assertThatCode(() -> service.validate(validPrice())).doesNotThrowAnyException();
    }

    @Test
    void writesBatchWithSingleMapperCall() {
        EtfPriceHistoryMapper mapper = mock(EtfPriceHistoryMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        service.upsertBatch(List.of(validPrice(), validPrice()));

        verify(mapper).upsertBatch(org.mockito.ArgumentMatchers.argThat(histories ->
                histories.size() == 2 && histories.stream().allMatch(history ->
                        "510300".equals(history.getFundCode())
                                && "QFQ".equals(history.getAdjustmentType()))));
    }

    @Test
    void rejectsInvalidOhlcRelationship() {
        EtfPriceHistoryIngestDTO dto = validPrice();
        dto.setHigh(new BigDecimal("0.99"));

        assertThatThrownBy(() -> service.validate(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OHLC");
    }

    @Test
    void rejectsUnknownAdjustmentTypeAndFutureDate() {
        EtfPriceHistoryIngestDTO unknownType = validPrice();
        unknownType.setAdjustmentType("HFQ");
        assertThatThrownBy(() -> service.validate(unknownType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QFQ");

        EtfPriceHistoryIngestDTO futurePrice = validPrice();
        futurePrice.setTradeDate(LocalDate.now().plusDays(1));
        assertThatThrownBy(() -> service.validate(futurePrice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tradeDate");
    }

    private EtfPriceHistoryIngestDTO validPrice() {
        EtfPriceHistoryIngestDTO dto = new EtfPriceHistoryIngestDTO();
        dto.setFundCode("510300");
        dto.setFundName("沪深300ETF");
        dto.setTradeDate(LocalDate.now());
        dto.setOpen(new BigDecimal("4.01"));
        dto.setHigh(new BigDecimal("4.10"));
        dto.setLow(new BigDecimal("3.98"));
        dto.setClose(new BigDecimal("4.08"));
        dto.setAdjustmentType("QFQ");
        dto.setSource("provider");
        dto.setFetchedAt(LocalDateTime.now());
        return dto;
    }
}
