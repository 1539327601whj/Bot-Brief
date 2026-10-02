package com.ai.daily.screener;

import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.service.MarketValuationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 读侧接上预取库之后的行为。**这是本次改动风险最高的一段**——动的是热点读取路径。
 *
 * <p>所以这里盯的不是「有没有省下外呼」，而是**两条边界**：
 * 该读库的时候一次外呼都不发；不该读库的时候（盘中、开关关掉、库里没有、库读挂了）
 * 行为与改动前**逐字一致**。后者才是这个分支的真正代价——多了一条可能让页面变空的路。
 *
 * <p>钟点由子类钉住，所以这些断言与「什么时候跑测试」无关（见
 * {@link StockScreenerService#localTimeNow()}）。
 *
 * <p>日期则相反，**故意不钉**：服务问的是「运行当天的快照有没有」，而机器日期改不得。
 * 所以 mock 一律用 {@code any(LocalDate.class)}——把日期写死的话，这条测试只在
 * 「跑它的那天正好等于写死的那个日期」时才走被测的那条分支，其余日子全在测回退路径，
 * 而且照样是绿的。
 */
class StockScreenerServicePrefetchReadTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 9, 30);
    private static final LocalTime AFTER_CLOSE = LocalTime.of(16, 0);
    private static final LocalTime IN_SESSION = LocalTime.of(10, 30);

    private MarketDataClient client;
    private ScreenerPrefetchService prefetchService;
    private MarketValuationHistoryService valuationService;
    private EtfPriceHistoryService priceService;
    private IndexFundPool indexPool;
    private AltQuoteSource alt;

    @BeforeEach
    void setUp() {
        client = mock(MarketDataClient.class);
        prefetchService = mock(ScreenerPrefetchService.class);
        valuationService = mock(MarketValuationHistoryService.class);
        priceService = mock(EtfPriceHistoryService.class);
        alt = mock(AltQuoteSource.class);
        indexPool = new IndexFundPool(
                new org.springframework.core.io.ClassPathResource("screener/index-pool.json"));

        when(client.fetchUniverse()).thenReturn(snapshot(List.of(row("600519", "贵州茅台"))));
        when(client.fetchEtfQuotes(any())).thenReturn(Map.of());
        when(client.fetchKline(anyString())).thenReturn(MarketDataClient.KlineOutcome.ok(List.of()));
        when(client.klineLimit()).thenReturn(250);
        when(priceService.latestBatch(any(), any(), any())).thenReturn(List.of());
        when(alt.fetchTencentQuotes(any())).thenReturn(Map.of());
        when(alt.fetchSinaQuotes(any())).thenReturn(Map.of());
        when(alt.fetchTencentKline(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("腾讯日线为空", AltQuoteSource.PROVIDER_TENCENT));
    }

    /**
     * 钟点与开关都做成入参，且钟点用子类钉住。**不要**改成「看现在几点再决定断言什么」——
     * 那种测试有一半时间什么也没测。
     */
    private StockScreenerService service(LocalTime at, boolean readEnabled) {
        return service(at, readEnabled, new ScreenerCache(15, 20, 60, 10));
    }

    private StockScreenerService service(LocalTime at, boolean readEnabled, ScreenerCache cache) {
        return new StockScreenerService(client, cache, valuationService, priceService, indexPool, alt,
                prefetchService, readEnabled, 3, 10) {
            @Override
            LocalTime localTimeNow() {
                return at;
            }
        };
    }

    // ================= 该读库：一次外呼都不发 =================

    @Test
    void afterCloseWithTodaysSnapshotTheUniverseCostsZeroOutboundCalls() {
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class)))
                .thenReturn(Optional.of(snapshot(List.of(row("600519", "贵州茅台")))));

        var result = service(AFTER_CLOSE, true).scan(ScreenerParams.defaults());

        verify(client, never()).fetchUniverse();
        assertThat(result.summary().scanned()).isEqualTo(1);
        assertThat(result.summary().notes())
                .anySatisfy(n -> assertThat(n).contains("本地预取库"))
                .anySatisfy(n -> assertThat(n).contains(TRADE_DATE.toString()));
    }

    @Test
    void thePrefetchedSnapshotAlsoFillsTheMemoryCacheSoTheNextClickReadsNeitherDbNorSource() {
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class)))
                .thenReturn(Optional.of(snapshot(List.of(row("600519", "贵州茅台")))));

        service(AFTER_CLOSE, true, cache).scan(ScreenerParams.defaults());

        assertThat(cache.universeFresh()).isTrue();
        assertThat(cache.universeOrStale()).isPresent();
    }

    /**
     * 读自己的库**不能**顺手解封东财。
     *
     * <p>这条在缓存层单独测过（{@code ScreenerCacheCooldownTest}），这里再走一遍完整点击：
     * 冷却中读到预取数据后，冷却必须还在——否则「预取读得越多、冷却被抹得越早」，
     * 玩家的下一次实时点击正好撞在限流上。
     */
    @Test
    void readingFromPrefetchWhileCoolingDownDoesNotClearTheCooldown() {
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);
        cache.enterCooldown();
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class)))
                .thenReturn(Optional.of(snapshot(List.of(row("600519", "贵州茅台")))));

        service(AFTER_CLOSE, true, cache).scan(ScreenerParams.defaults());

        assertThat(cache.inCooldown()).isTrue();
    }

    // ================= 不该读库：与改动前逐字一致 =================

    @Test
    void inSessionNothingAboutThePrefetchStoreIsTouched() {
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class)))
                .thenReturn(Optional.of(snapshot(List.of(row("600519", "贵州茅台")))));

        service(IN_SESSION, true).scan(ScreenerParams.defaults());

        // 盘中要的是现价位置。库里有今天的行也不能读——那是 15:00 的收盘价。
        verify(client, times(1)).fetchUniverse();
        // 连「今天有没有快照」都不该问：判据在钟点这一项就断了，库一次都不碰
        verify(prefetchService, never()).hasSnapshotFor(any());
        verify(prefetchService, never()).snapshotFor(any());
    }

    @Test
    void theKillSwitchRestoresTheOldBehaviourAndNeverTouchesTheStore() {
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class)))
                .thenReturn(Optional.of(snapshot(List.of(row("600519", "贵州茅台")))));

        var result = service(AFTER_CLOSE, false).scan(ScreenerParams.defaults());

        verify(client, times(1)).fetchUniverse();
        verify(prefetchService, never()).hasSnapshotFor(any());
        verify(prefetchService, never()).snapshotFor(any());
        assertThat(result.summary().notes())
                .noneSatisfy(n -> assertThat(n).contains("本地预取库"));
    }

    @Test
    void anEmptyStoreFallsBackToTheLivePathWithTheSameOutboundCostAsBefore() {
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(false);
        when(prefetchService.snapshotFor(any(LocalDate.class))).thenReturn(Optional.empty());

        var result = service(AFTER_CLOSE, true).scan(ScreenerParams.defaults());

        verify(client, times(1)).fetchUniverse();
        assertThat(result.summary().scanned()).isEqualTo(1);
        assertThat(result.summary().notes())
                .noneSatisfy(n -> assertThat(n).contains("本地预取库"));
    }

    /**
     * 预取表出问题**不许**让页面变空或报错。
     *
     * <p>库连不上、表还没建、迁移没跑，都只该让这一次点击多几次外呼。把一次可降级的优化
     * 变成一次错误框，等于拿「省外呼」换掉了「页面能用」。
     */
    @Test
    void aDatabaseFailureFallsBackToLiveAndStillReturnsAResult() {
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class)))
                .thenThrow(new RuntimeException("Table 'screener_universe_snapshot' doesn't exist"));

        var result = service(AFTER_CLOSE, true).scan(ScreenerParams.defaults());

        verify(client, times(1)).fetchUniverse();
        assertThat(result.summary().scanned()).isEqualTo(1);
        assertThat(result.disclaimer()).isNotBlank();
    }

    /** 判据说「库里有」、读的时候却没了（期间被清过表）。退回实时，不报错。 */
    @Test
    void aStoreThatDisappearedBetweenTheTwoQueriesFallsBackInsteadOfThrowing() {
        when(prefetchService.hasSnapshotFor(any(LocalDate.class))).thenReturn(true);
        when(prefetchService.snapshotFor(any(LocalDate.class))).thenReturn(Optional.empty());

        var result = service(AFTER_CLOSE, true).scan(ScreenerParams.defaults());

        verify(client, times(1)).fetchUniverse();
        assertThat(result.summary().scanned()).isEqualTo(1);
    }

    // ================= 夹具 =================

    private static MarketDataClient.UniverseSnapshot snapshot(List<StockRow> rows) {
        return new MarketDataClient.UniverseSnapshot(rows, rows.size(), 0, TRADE_DATE,
                new BigDecimal("38300000000"));
    }

    private static StockRow row(String code, String name) {
        StockRow r = new StockRow();
        r.setCode(code);
        r.setName(name);
        r.setMarket(1);
        r.setIndustry("白酒");
        r.setPrice(new BigDecimal("12.50"));
        r.setAmount(new BigDecimal("500000000"));
        r.setTurnoverRate(new BigDecimal("2"));
        r.setTotalMarketCap(new BigDecimal("50000000000"));
        r.setFloatMarketCap(new BigDecimal("40000000000"));
        r.setPb(new BigDecimal("1.5"));
        r.setPeTtm(new BigDecimal("6"));
        r.setRoe(new BigDecimal("12"));
        r.setRevenueGrowth(new BigDecimal("8"));
        r.setProfitGrowth(new BigDecimal("10"));
        r.setGrossMargin(new BigDecimal("30"));
        r.setDebtRatio(new BigDecimal("45"));
        r.setDividendYield(new BigDecimal("2.5"));
        r.setListDate(LocalDate.of(2010, 1, 1));
        return r;
    }
}