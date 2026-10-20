package com.ai.daily.screener.v2;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.screener.AltQuoteSource;
import com.ai.daily.screener.MarketDataClient;
import com.ai.daily.screener.ScreenerCache;
import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 池外 ETF 的腾讯日线补齐。盯住四件会静默出错的事：
 * 翻页去重、坏 OHLC 丢掉、限流后保留已拿到的页、失败后的负缓存。
 */
class EtfLiveKlineServiceTest {

    private static final String CODE = "513180";
    private static final InstrumentCode ETF =
            new InstrumentCode("ETF", CODE, "SH");

    private AltQuoteSource altQuoteSource;
    private EtfPriceHistoryService historyService;
    private ScreenerCache cache;
    private WarehouseProperties properties;

    @BeforeEach
    void setUp() {
        altQuoteSource = mock(AltQuoteSource.class);
        historyService = mock(EtfPriceHistoryService.class);
        cache = mock(ScreenerCache.class);
        properties = new WarehouseProperties();
        when(cache.inCooldown(anyString())).thenReturn(false);
    }

    private EtfLiveKlineService service() {
        return new EtfLiveKlineService(altQuoteSource, historyService, properties, cache, 2600);
    }

    @Test
    void pagesUntilLookupLimitAndPersistsInBatches() {
        AtomicInteger page = new AtomicInteger();
        when(altQuoteSource.fetchTencentKlineRows(eq(CODE), any(), eq(800)))
                .thenAnswer(invocation -> {
                    int index = page.getAndIncrement();
                    int size = index < 3 ? 800 : 200;
                    return MarketDataClient.KlineRowsOutcome.ok(
                            chunk(index * 800, size), AltQuoteSource.PROVIDER_TENCENT);
                });

        List<WarehouseRows.PriceBar> bars = service().fetchAndStore(ETF, "恒生科技ETF华夏", true);

        assertThat(page.get()).isEqualTo(4);
        assertThat(bars).hasSize(2600);
        assertThat(bars).isSortedAccordingTo(
                (a, b) -> a.tradeDate().compareTo(b.tradeDate()));
        assertThat(bars).allSatisfy(bar -> {
            assertThat(bar.source()).isEqualTo(EtfLiveKlineService.SOURCE_TENCENT_QFQ);
            // 腾讯行有完整 OHLC，不能只剩收盘价。
            assertThat(bar.openPrice()).isNotNull();
            assertThat(bar.highPrice()).isNotNull();
            assertThat(bar.lowPrice()).isNotNull();
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EtfPriceHistoryIngestDTO>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(historyService, times(11)).upsertBatch(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(batch ->
                assertThat(batch).hasSizeLessThanOrEqualTo(EtfLiveKlineService.INGEST_BATCH_SIZE));
        int persisted = captor.getAllValues().stream().mapToInt(List::size).sum();
        assertThat(persisted).isEqualTo(2600);
        assertThat(captor.getAllValues().get(0).get(0).getSource())
                .isEqualTo(EtfLiveKlineService.SOURCE_TENCENT_QFQ);
    }

    @Test
    void stopsWhenAPageIsShorterThanTheRequestedSize() {
        AtomicInteger calls = new AtomicInteger();
        when(altQuoteSource.fetchTencentKlineRows(eq(CODE), any(), eq(800)))
                .thenAnswer(invocation -> {
                    int index = calls.getAndIncrement();
                    return MarketDataClient.KlineRowsOutcome.ok(
                            chunk(index * 800, index == 0 ? 800 : 300),
                            AltQuoteSource.PROVIDER_TENCENT);
                });

        List<WarehouseRows.PriceBar> bars = service().fetchAndStore(ETF, null, true);

        assertThat(bars).hasSize(1100);
        // 第二页已经到头，再问第三页只会拿到空响应。
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void keepsFirstPageWhenTheSecondIsThrottled() {
        AtomicInteger calls = new AtomicInteger();
        when(altQuoteSource.fetchTencentKlineRows(eq(CODE), any(), eq(800)))
                .thenAnswer(invocation -> calls.getAndIncrement() == 0
                        ? MarketDataClient.KlineRowsOutcome.ok(chunk(0, 800),
                        AltQuoteSource.PROVIDER_TENCENT)
                        : MarketDataClient.KlineRowsOutcome.throttled("429",
                        AltQuoteSource.PROVIDER_TENCENT));

        List<WarehouseRows.PriceBar> bars = service().fetchAndStore(ETF, null, true);

        assertThat(bars).hasSize(800);
        verify(historyService, atLeastOnce()).upsertBatch(any());
        verify(cache).enterCooldown(AltQuoteSource.PROVIDER_TENCENT);
    }

    @Test
    void dropsRowsWithImpossibleOhlcWithoutLosingTheRest() {
        List<MarketDataClient.KlineRow> rows = new ArrayList<>(chunk(0, 10));
        // 最高价低于开盘价：批量接口会整批拒绝，必须在这里就剔掉。
        rows.set(3, new MarketDataClient.KlineRow(rows.get(3).tradeDate(),
                bd("2"), bd("1"), bd("0.5"), bd("2"), bd("100"), null, null, null));
        when(altQuoteSource.fetchTencentKlineRows(eq(CODE), any(), eq(800)))
                .thenReturn(MarketDataClient.KlineRowsOutcome.ok(rows,
                        AltQuoteSource.PROVIDER_TENCENT));

        List<WarehouseRows.PriceBar> bars = service().fetchAndStore(ETF, null, true);

        assertThat(bars).hasSize(9);
        assertThat(bars).noneMatch(bar -> bar.tradeDate().equals(rows.get(3).tradeDate()));
    }

    @Test
    void failureIsNotRetriedWithinTheTtl() {
        when(altQuoteSource.fetchTencentKlineRows(eq(CODE), any(), eq(800)))
                .thenReturn(MarketDataClient.KlineRowsOutcome.failed("boom",
                        AltQuoteSource.PROVIDER_TENCENT));
        EtfLiveKlineService service = service();

        assertThat(service.fetchAndStore(ETF, null, true)).isEmpty();
        assertThat(service.fetchAndStore(ETF, null, true)).isEmpty();
        verify(altQuoteSource, times(1)).fetchTencentKlineRows(eq(CODE), any(), eq(800));
    }

    @Test
    void onDemandDisabledMakesNoCallsAtAll() {
        properties.setOnDemandEnabled(false);

        assertThat(service().fetchAndStore(ETF, null, true)).isEmpty();
        verify(altQuoteSource, never()).fetchTencentKlineRows(anyString(), any(), eq(800));
    }

    /** 生成从 {@code base} 往前数、连续自然日的 {@code count} 根合法日线。 */
    private static List<MarketDataClient.KlineRow> chunk(int offset, int count) {
        LocalDate base = LocalDate.now().minusDays(1);
        List<MarketDataClient.KlineRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate date = base.minusDays(offset + i);
            rows.add(new MarketDataClient.KlineRow(date, bd("1.00"), bd("1.05"),
                    bd("0.95"), bd("1.02"), bd("1000"), null, null, null));
        }
        return rows;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
