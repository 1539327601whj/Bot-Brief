package com.ai.daily.screener.v2;

import com.ai.daily.entity.EtfPriceHistory;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.service.MarketValuationHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.InstrumentResolver;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 图表序列的降采样与周线聚合。
 *
 * <p>周线不是「每五根取一根」：直接抽最后一根，周最高价会低于日内高点，
 * 支撑位就会算错。这里逐字段钉住开/高/低/收/量/额各自的口径。
 */
class SeriesServiceTest {

    private static CodeLookupV2DTO.PricePoint price(String date, String open, String high,
                                                    String low, String close, String volume) {
        return new CodeLookupV2DTO.PricePoint(date, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), new BigDecimal(volume),
                new BigDecimal("1000"), new BigDecimal("0.5"), new BigDecimal("1.2"));
    }

    @Test
    void weeklyPricesAggregateOhlcAndSumVolume() {
        List<CodeLookupV2DTO.PricePoint> daily = List.of(
                price("2026-10-12", "10", "10.8", "9.9", "10.5", "100"),   // Mon
                price("2026-10-13", "10.5", "11.2", "10.2", "11.0", "200"), // Tue
                price("2026-10-14", "11.0", "11.0", "10.1", "10.4", "300"), // Wed
                price("2026-10-15", "10.4", "10.9", "10.0", "10.8", "400"), // Thu
                price("2026-10-16", "10.8", "11.5", "10.7", "11.4", "500")); // Fri

        List<CodeLookupV2DTO.PricePoint> weekly = SeriesService.weeklyPrices(daily);

        assertThat(weekly).hasSize(1);
        CodeLookupV2DTO.PricePoint week = weekly.get(0);
        assertThat(week.date()).isEqualTo("2026-10-16");
        assertThat(week.open()).isEqualByComparingTo("10");
        assertThat(week.high()).isEqualByComparingTo("11.5");
        assertThat(week.low()).isEqualByComparingTo("9.9");
        assertThat(week.close()).isEqualByComparingTo("11.4");
        assertThat(week.volume()).isEqualByComparingTo("1500");
        assertThat(week.amount()).isEqualByComparingTo("5000");
    }

    @Test
    void weeklyPricesSplitAtIsoMonday() {
        List<CodeLookupV2DTO.PricePoint> daily = List.of(
                price("2026-10-16", "10", "10", "10", "10", "1"), // Fri, week of 10-12
                price("2026-10-19", "11", "11", "11", "11", "2"), // Mon, next week
                price("2026-10-20", "12", "12", "12", "12", "3")); // Tue

        List<CodeLookupV2DTO.PricePoint> weekly = SeriesService.weeklyPrices(daily);

        assertThat(weekly).hasSize(2);
        assertThat(weekly.get(0).close()).isEqualByComparingTo("10");
        assertThat(weekly.get(1).open()).isEqualByComparingTo("11");
        assertThat(weekly.get(1).close()).isEqualByComparingTo("12");
        assertThat(weekly.get(1).volume()).isEqualByComparingTo("5");
    }

    @Test
    void weeklyFlowsSumEachBucket() {
        List<CodeLookupV2DTO.MoneyFlowPoint> daily = List.of(
                new CodeLookupV2DTO.MoneyFlowPoint("2026-10-12",
                        new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("2"),
                        new BigDecimal("3"), new BigDecimal("4")),
                new CodeLookupV2DTO.MoneyFlowPoint("2026-10-13",
                        new BigDecimal("-4"), new BigDecimal("2"), new BigDecimal("3"),
                        new BigDecimal("4"), new BigDecimal("5")));

        List<CodeLookupV2DTO.MoneyFlowPoint> weekly = SeriesService.weeklyFlows(daily);

        assertThat(weekly).hasSize(1);
        assertThat(weekly.get(0).date()).isEqualTo("2026-10-13");
        assertThat(weekly.get(0).mainNet()).isEqualByComparingTo("6");
        assertThat(weekly.get(0).superNet()).isEqualByComparingTo("3");
    }

    @Test
    void intervalDowngradesToWeeklyOnlyWhenTooLong() {
        assertThat(SeriesService.resolveInterval("1d", "1y", 300)).isEqualTo("1d");
        assertThat(SeriesService.resolveInterval(null, "1y", 300)).isEqualTo("1d");
        assertThat(SeriesService.resolveInterval("1d", "10y", 2400)).isEqualTo("1w");
        assertThat(SeriesService.resolveInterval("1d", "3y", 2000)).isEqualTo("1w");
        assertThat(SeriesService.resolveInterval("1w", "1y", 10)).isEqualTo("1w");
        assertThatThrownBy(() -> SeriesService.resolveInterval("5m", "1y", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval");
    }

    @Test
    void rangeParsingRejectsUnknownValues() {
        assertThat(SeriesService.normaliseRange(null)).isEqualTo("1y");
        assertThat(SeriesService.normaliseRange(" 3Y ")).isEqualTo("3y");
        assertThat(SeriesService.years("10y")).isEqualTo(10);
        assertThatThrownBy(() -> SeriesService.normaliseRange("all"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    @Test
    void emptySeriesIsNotAnError() {
        assertThat(SeriesService.weeklyPrices(List.of())).isEmpty();
        assertThat(SeriesService.weeklyFlows(List.of())).isEmpty();
    }

    @Test
    void etfChartUsesTheSamePreferredSeriesAsTheScore() {
        // md_price_daily 只有一小段旧东财日线，etf_price_history 里有更长、更新的一批。
        // 图表必须选后一条：否则会出现「分数用 2600 根腾讯、图上只画了 20 根东财」。
        InstrumentResolver resolver = mock(InstrumentResolver.class);
        WarehouseRepository repository = mock(WarehouseRepository.class);
        EtfPriceHistoryService etfHistory = mock(EtfPriceHistoryService.class);
        WarehouseSchemaGuard guard = mock(WarehouseSchemaGuard.class);
        WarehouseProperties properties = mock(WarehouseProperties.class);
        when(properties.isEnabled()).thenReturn(true);
        when(guard.isReady()).thenReturn(true);
        InstrumentCode etf = new InstrumentCode("ETF", "510300", "SH");
        when(resolver.resolve(anyString())).thenReturn(etf);
        when(repository.pricesBetween(any(), anyString(), any(), any()))
                .thenReturn(eastmoneyBars(20, LocalDate.now().minusDays(6)));
        when(etfHistory.latestBatch(any(), any(), anyString()))
                .thenReturn(prefetchedBars(60, LocalDate.now().minusDays(1)));
        SeriesService service = new SeriesService(resolver, repository,
                mock(MarketValuationHistoryService.class), etfHistory,
                mock(IndexFundPool.class), guard, properties);

        CodeLookupV2DTO.SeriesView view = service.series("510300", "price", "1y", null);

        assertThat(view.price()).hasSize(60);
        assertThat(view.price().get(59).date()).isEqualTo(LocalDate.now().minusDays(1).toString());
        assertThat(view.note()).contains("预取库");
        // 预取库那条带完整 OHLC，不能在图里退化成只有收盘价。
        assertThat(view.price().get(59).open()).isNotNull();
        assertThat(view.price().get(59).high()).isNotNull();
        assertThat(view.price().get(59).low()).isNotNull();
    }

    private static List<WarehouseRows.PriceBar> eastmoneyBars(int count, LocalDate last) {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bars.add(new WarehouseRows.PriceBar("sh510300", last.minusDays(count - 1L - i),
                    "QFQ", bd("4"), bd("4.1"), bd("3.9"), bd("4.05"), bd("1000"), null,
                    null, null, "eastmoney-kline", LocalDateTime.now()));
        }
        return bars;
    }

    private static List<EtfPriceHistory> prefetchedBars(int count, LocalDate last) {
        List<EtfPriceHistory> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            EtfPriceHistory row = new EtfPriceHistory();
            row.setFundCode("510300");
            row.setFundName("沪深300ETF");
            row.setTradeDate(last.minusDays(count - 1L - i));
            row.setOpen(bd("4"));
            row.setHigh(bd("4.1"));
            row.setLow(bd("3.9"));
            row.setClose(bd("4.05"));
            row.setAdjustmentType("QFQ");
            row.setSource("腾讯前复权日线");
            row.setFetchedAt(LocalDateTime.now());
            rows.add(row);
        }
        return rows;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
