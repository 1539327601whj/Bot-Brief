package com.ai.daily.screener.v2;

import com.ai.daily.screener.AltQuoteSource;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.screener.MarketDataClient;
import com.ai.daily.screener.MarketDataException;
import com.ai.daily.screener.ScreenerCache;
import com.ai.daily.screener.StockValuationClient;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按需同步的三条纪律：**限流不重试、没有就是没有、本地有就先用本地**。
 *
 * <p>这里不接真网络。断言的重点不是「数据长什么样」，而是**这一轮到底发了几个请求、
 * 最后抛的是哪种异常**——因为调用方（controller / 页面）完全靠这个决定下一步动作。
 */
class OnDemandSyncServiceTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final InstrumentCode MAOTAI = InstrumentCode.stock("600519", "SH");

    private MarketDataClient marketDataClient;
    private AltQuoteSource altQuoteSource;
    private StockValuationClient stockValuationClient;
    private WarehouseRepository repository;
    private ScreenerCache cache;
    private WarehouseProperties properties;
    private IndexFundPool indexFundPool;
    private OnDemandSyncService service;

    @BeforeEach
    void setUp() {
        marketDataClient = mock(MarketDataClient.class);
        altQuoteSource = mock(AltQuoteSource.class);
        stockValuationClient = mock(StockValuationClient.class);
        repository = mock(WarehouseRepository.class);
        cache = mock(ScreenerCache.class);
        properties = new WarehouseProperties();
        indexFundPool = mock(IndexFundPool.class);
        service = new OnDemandSyncService(marketDataClient, altQuoteSource, stockValuationClient,
                repository, cache, properties, indexFundPool);

        when(cache.inCooldown(anyString())).thenReturn(false);
        when(cache.cooldownRemainingMinutes(anyString())).thenReturn(5L);
        when(altQuoteSource.fetchTencentQuotesBySymbols(anyList())).thenReturn(Map.of());
        when(altQuoteSource.fetchSinaQuotesBySymbols(anyList())).thenReturn(Map.of());
        when(marketDataClient.fetchKlineRows(anyString(), anyInt()))
                .thenReturn(MarketDataClient.KlineRowsOutcome.failed("日线源不可用"));
        when(stockValuationClient.fetchHistory(anyString()))
                .thenReturn(new StockValuationClient.History("600519", null, List.of(), 0,
                        "估值源不可用"));
        when(repository.latestPriceDate(anyString())).thenReturn(null);
        when(repository.countPrices(anyString())).thenReturn(0L);
        when(repository.latestPrice(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.latestValuation(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.financialsAvailableAsOf(anyString(), any(), anyInt())).thenReturn(List.of());
        when(repository.moneyflow(anyString(), anyInt())).thenReturn(List.of());
    }

    /** 东财限流：只发一次，进入冷却，抛 429（不是 404、不是 503）。 */
    @Test
    void rateLimitedQuoteIsNeverRetriedAndSurfacesAsRateLimit() {
        when(marketDataClient.fetchQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("429"));

        assertThatThrownBy(() -> service.sync(MAOTAI, null))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .hasMessageContaining("冷却");

        // times(1) 就是「绝不重试」：被限流后再点一次，也不该在这一次查询里重发。
        verify(marketDataClient, times(1)).fetchQuotes(anyList());
        verify(cache).enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
    }

    /** 源明确回答「没有这一行」→ 404 语义；不能把它说成「网络失败」。 */
    @Test
    void absentInstrumentBecomesNotFound() {
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.sync(MAOTAI, null))
                .isInstanceOf(InstrumentNotFoundException.class)
                .hasMessageContaining("查不到代码");

        verify(marketDataClient, times(1)).fetchQuotes(anyList());
    }

    /** 行情源没给到、本地有最近收盘价：返回 PARTIAL，不抛异常，也不把本地价说成现价。 */
    @Test
    void localPriceKeepsTheQueryAliveButIsMarkedPartial() {
        LocalDate today = LocalDate.now(SHANGHAI);
        WarehouseRows.PriceBar bar = new WarehouseRows.PriceBar("STOCK:600519", today, "QFQ",
                new BigDecimal("1500"), new BigDecimal("1520"), new BigDecimal("1490"),
                new BigDecimal("1510"), new BigDecimal("1000"), new BigDecimal("1500000"),
                new BigDecimal("0.3"), new BigDecimal("0.8"), "eastmoney-kline", null);
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(Map.of());
        when(repository.latestPrice(anyString(), anyString())).thenReturn(Optional.of(bar));
        when(repository.latestPriceDate(anyString())).thenReturn(today.toString());
        when(repository.countPrices(anyString())).thenReturn(300L);
        when(repository.latestValuation(anyString(), anyString())).thenReturn(Optional.of(
                new WarehouseRows.ValuationPoint("STOCK:600519", today,
                        new BigDecimal("22"), new BigDecimal("8"), null,
                        new BigDecimal("1.8"), new BigDecimal("1510"), null,
                        OnDemandSyncService.VALUATION_SOURCE, LocalDateTime.now())));

        OnDemandSyncService.SyncReport report = service.sync(MAOTAI, null);

        assertThat(report.quote()).isNotNull();
        assertThat(report.quote().provider()).isEqualTo("local");
        assertThat(report.quote().price()).isEqualByComparingTo("1510");
        assertThat(report.quoteStatus()).isEqualTo(DataStatus.PARTIAL);
        // 本地只有 300 根、上市日期未知：会尝试补一次长历史；源不可用时沿用本地日线，
        // 日线状态如实是 PARTIAL，而不是把 300 根说成完整历史。
        assertThat(report.priceStatus()).isEqualTo(DataStatus.PARTIAL);
        verify(marketDataClient, times(1)).fetchKlineRows(anyString(), anyInt());
        assertThat(report.valuationStatus()).isEqualTo(DataStatus.OK);
        // 这一轮没有从源拿到任何新数据，页面据此显示「来自本地仓库」。
        assertThat(report.fetchedFromSource()).isFalse();
        assertThat(report.retryAfterMs()).isZero();
    }

    /**
     * 东财冷却中、但本地日线新鲜且足够长：不碰日线/估值接口。
     *
     * <p>这条是「蚂蚁搬家」的核心：第二次查询应当只读库。
     * 600 根是长历史的门槛——300 根会触发一次补历史，见下方用例。
     */
    @Test
    void longFreshLocalDataAvoidsPriceAndValuationCalls() {
        LocalDate today = LocalDate.now(SHANGHAI);
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(Map.of());
        when(repository.latestPrice(anyString(), anyString())).thenReturn(Optional.of(
                new WarehouseRows.PriceBar("STOCK:600519", today, "QFQ",
                        new BigDecimal("1500"), new BigDecimal("1520"), new BigDecimal("1490"),
                        new BigDecimal("1510"), new BigDecimal("1000"), new BigDecimal("1500000"),
                        new BigDecimal("0.3"), new BigDecimal("0.8"), "eastmoney-kline", null)));
        when(repository.latestPriceDate(anyString())).thenReturn(today.toString());
        when(repository.countPrices(anyString())).thenReturn(600L);
        when(repository.latestValuation(anyString(), anyString())).thenReturn(Optional.of(
                new WarehouseRows.ValuationPoint("STOCK:600519", today,
                        new BigDecimal("22"), new BigDecimal("8"), null,
                        new BigDecimal("1.8"), new BigDecimal("1510"), null,
                        OnDemandSyncService.VALUATION_SOURCE, LocalDateTime.now())));

        service.sync(MAOTAI, null);

        verify(marketDataClient, never()).fetchKlineRows(anyString(), anyInt());
        verify(stockValuationClient, never()).fetchHistory(anyString());
    }

    /** 本地日线新鲜但只有 300 根：补一次长历史；同一进程内 6 小时内不重复补。 */
    @Test
    void shortFreshHistoryBackfillsOnceThenStops() {
        LocalDate today = LocalDate.now(SHANGHAI);
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(Map.of());
        when(repository.latestPrice(anyString(), anyString())).thenReturn(Optional.of(
                new WarehouseRows.PriceBar("STOCK:600519", today, "QFQ",
                        new BigDecimal("1500"), new BigDecimal("1520"), new BigDecimal("1490"),
                        new BigDecimal("1510"), new BigDecimal("1000"), new BigDecimal("1500000"),
                        new BigDecimal("0.3"), new BigDecimal("0.8"), "eastmoney-kline", null)));
        when(repository.latestPriceDate(anyString())).thenReturn(today.toString());
        when(repository.countPrices(anyString())).thenReturn(300L);
        when(repository.latestValuation(anyString(), anyString())).thenReturn(Optional.of(
                new WarehouseRows.ValuationPoint("STOCK:600519", today,
                        new BigDecimal("22"), new BigDecimal("8"), null,
                        new BigDecimal("1.8"), new BigDecimal("1510"), null,
                        OnDemandSyncService.VALUATION_SOURCE, LocalDateTime.now())));

        service.sync(MAOTAI, null);
        service.sync(MAOTAI, null);

        verify(marketDataClient, times(1)).fetchKlineRows(anyString(), anyInt());
    }
}
