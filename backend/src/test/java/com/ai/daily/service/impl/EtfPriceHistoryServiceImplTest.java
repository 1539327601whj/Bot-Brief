package com.ai.daily.service.impl;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.mapper.EtfPriceHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // ================= 读：指数池整块一次取回 =================

    @Test
    void anEmptyCodeListNeverReachesSql() {
        EtfPriceHistoryMapper mapper = mock(EtfPriceHistoryMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        // `IN ()` 是语法错误：空入参落到 SQL 会把「这次没有指数要读」变成一次异常
        assertThat(service.latestBatch(List.of(), LocalDate.now().minusDays(500), "QFQ")).isEmpty();
        assertThat(service.latestBatch(null, LocalDate.now().minusDays(500), "QFQ")).isEmpty();

        verify(mapper, never()).latestBatch(anyList(), any(), anyString());
    }

    @Test
    void blankCodesAreDroppedAndDuplicatesCollapsedBeforeTheQuery() {
        EtfPriceHistoryMapper mapper = mock(EtfPriceHistoryMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.latestBatch(anyList(), any(), anyString())).thenReturn(List.of());

        service.latestBatch(List.of("510300", " 510300 ", "  ", "159915"),
                LocalDate.now().minusDays(500), "qfq");

        verify(mapper).latestBatch(org.mockito.ArgumentMatchers.argThat(codes -> {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) codes;
            return list.equals(List.of("510300", "159915"));
        }), any(), org.mockito.ArgumentMatchers.eq("QFQ"));
    }

    @Test
    void anOversizedCodeListIsSplitByTheBatchLimit() {
        EtfPriceHistoryMapper mapper = mock(EtfPriceHistoryMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.latestBatch(anyList(), any(), anyString())).thenReturn(List.of());

        List<String> codes = new ArrayList<>();
        for (int i = 0; i < EtfPriceHistoryServiceImpl.MAX_BATCH + 1; i++) {
            codes.add("51%04d".formatted(i));
        }
        service.latestBatch(codes, LocalDate.now().minusDays(500), "QFQ");

        // 251 个代码 → 两次查询。池子按 200 条设计，但「长大的那天就查不动了」
        // 不该是它退化的方式
        verify(mapper, org.mockito.Mockito.times(2)).latestBatch(anyList(), any(), anyString());
    }

    @Test
    void theReadPathRejectsANonQfqAdjustmentToo() {
        // 读也要挡：把 HFQ 的曲线当成前复权来算价格位置，会在除权日显示假跌，
        // 把正常分红判成「便宜」——而且每个数看上去都正常
        assertThatThrownBy(() -> service.latestBatch(List.of("510300"), LocalDate.now(), "HFQ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QFQ");
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
