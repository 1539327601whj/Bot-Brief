package com.ai.daily.screener;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockScreenerServiceTest {

    private MarketDataClient client;
    private MarketValuationHistoryService valuationService;

    @BeforeEach
    void setUp() {
        client = mock(MarketDataClient.class);
        valuationService = mock(MarketValuationHistoryService.class);
        when(client.fetchEtfQuotes(any())).thenReturn(Map.of());
        when(client.fetchKline(anyString())).thenReturn(klineOk());
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static MarketDataClient.KlineOutcome klineOk() {
        List<PricePositionCalculator.Bar> bars = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(300);
        for (int i = 0; i < 250; i++) {
            // 缓步下行：贴着近一年低位，正是「价格位置」这一维想要的形态
            bars.add(new PricePositionCalculator.Bar(start.plusDays(i),
                    BigDecimal.valueOf(20 - i * 0.03)));
        }
        return MarketDataClient.KlineOutcome.ok(bars);
    }

    private StockScreenerService service(int fetchThreads) {
        return service(fetchThreads, new ScreenerCache(15, 20, 10));
    }

    /** 让需要自己拿住缓存的测试（冷却那几条）能注入实例。 */
    private StockScreenerService service(int fetchThreads, ScreenerCache cache) {
        return new StockScreenerService(client, cache, valuationService, fetchThreads);
    }

    private static MarketDataClient.UniverseSnapshot snapshot(List<StockRow> rows) {
        return new MarketDataClient.UniverseSnapshot(rows, rows.size(), 0,
                LocalDate.of(2026, 9, 30), new BigDecimal("38300000000"));
    }

    /** 一批能通过全部排雷的股票，PE 各不相同，好让池内分位有区分度。 */
    private static List<StockRow> qualifiedUniverse(int count) {
        List<StockRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StockRow r = new StockRow();
            r.setCode(String.format("6005%02d", i));
            r.setName("合格" + i);
            r.setMarket(1);
            r.setIndustry("行业" + i);
            r.setPrice(new BigDecimal("12.50"));
            r.setAmount(new BigDecimal("500000000"));
            r.setTurnoverRate(new BigDecimal("2"));
            r.setTotalMarketCap(new BigDecimal("50000000000"));
            r.setFloatMarketCap(new BigDecimal("40000000000"));
            r.setPb(new BigDecimal("1.5"));
            r.setPeTtm(new BigDecimal(6 + i));
            r.setRoe(new BigDecimal("12"));
            r.setRevenueGrowth(new BigDecimal("8"));
            r.setProfitGrowth(new BigDecimal("10"));
            r.setGrossMargin(new BigDecimal("30"));
            r.setDebtRatio(new BigDecimal("45"));
            r.setDividendYield(new BigDecimal("2.5"));
            r.setListDate(LocalDate.of(2010, 1, 1));
            rows.add(r);
        }
        return rows;
    }

    // ================= 取数失败必须报错 =================

    @Test
    void unreachableMarketSourceThrowsInsteadOfReturningAnEmptyResult() {
        when(client.fetchUniverse()).thenThrow(new MarketDataException("行情源不可达"));
        assertThatThrownBy(() -> service(3).scan(ScreenerParams.defaults()))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("不可达");
    }

    @Test
    void emptyUniverseIsTreatedAsAnErrorNotAsNoCandidatesToday() {
        when(client.fetchUniverse()).thenReturn(snapshot(List.of()));
        assertThatThrownBy(() -> service(3).scan(ScreenerParams.defaults()))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("未执行");
    }

    // ================= 日线失败要降级而不是淘汰 =================

    @Test
    void klineFailureKeepsTheStockAndRecordsADegradation() {
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(client.fetchKline(anyString()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("日线不可达，价格位置未确认"));

        ScreenerResultDTO result = service(3).scan(ScreenerParams.defaults());

        assertThat(result.steadyStocks()).isNotEmpty();
        assertThat(result.summary().degradations())
                .anyMatch(d -> d.contains("日线未取到"));
        assertThat(result.priceAsOf()).isEqualTo("未取到");
    }

    // ================= 缓存 =================

    @Test
    void secondScanSameDayReusesCacheAndDoesNotCallOutAgain() {
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        StockScreenerService svc = service(3);

        svc.scan(ScreenerParams.defaults());
        long klinesAfterFirst = callsTo("fetchKline");
        assertThat(klinesAfterFirst).isPositive();

        ScreenerResultDTO second = svc.scan(ScreenerParams.defaults());

        verify(client, times(1)).fetchUniverse();       // 全市场快照走缓存
        assertThat(callsTo("fetchKline")).isEqualTo(klinesAfterFirst); // 日线走缓存
        assertThat(second.steadyStocks()).isNotEmpty();
        // ETF 当日行情每次实时取，价格不该是上一小时的
        verify(client, times(2)).fetchEtfQuotes(any());
    }

    private long callsTo(String methodName) {
        return org.mockito.Mockito.mockingDetails(client).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals(methodName))
                .count();
    }

    // ================= 指数基金优先 =================

    @Test
    void indexOnlyModeSkipsTheStockUniverseEntirely() {
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        verify(client, never()).fetchUniverse();
        assertThat(result.indexFunds()).hasSize(IndexFundPool.FUNDS.size());
        assertThat(result.steadyStocks()).isEmpty();
        assertThat(result.growthStocks()).isEmpty();
        assertThat(result.summary().scanned()).isZero();
        assertThat(result.summary().notes()).anyMatch(n -> n.contains("只筛指数基金"));
    }

    @Test
    void indexFundPercentileIsOnlyShownForIndicesThatActuallyHaveIt() {
        MarketValuationHistory row = new MarketValuationHistory();
        row.setIndexCode("SH000300");
        row.setIndexName("沪深300");
        row.setPeTtm(new BigDecimal("12.34"));
        row.setPePercentile(new BigDecimal("38.5"));
        row.setPercentileMethod("CSI_PE_TTM_ROLLING_10Y");
        row.setTradeDate(LocalDate.now().minusDays(1));
        when(valuationService.latest("SH000300", "CSI_PE_TTM_ROLLING_10Y", 1)).thenReturn(List.of(row));

        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");
        ScreenerResultDTO result = service(3).scan(p);

        ScreenerResultDTO.IndexFundItem hs300 = result.indexFunds().stream()
                .filter(f -> f.code().equals("510300")).findFirst().orElseThrow();
        assertThat(hs300.pePercentile()).isEqualByComparingTo("38.5");
        assertThat(hs300.percentileStatus()).contains("已接入");
        assertThat(hs300.notes()).anyMatch(n -> n.contains("不可与其他指数的 PE 直接比较"));

        ScreenerResultDTO.IndexFundItem other = result.indexFunds().stream()
                .filter(f -> f.code().equals("588000")).findFirst().orElseThrow();
        assertThat(other.pePercentile()).isNull();
        assertThat(other.percentileStatus()).isEqualTo(IndexFundPool.PERCENTILE_NOT_WIRED);
    }

    @Test
    void indexFundsAreBlockedOutWhenUserOnlyWantsStocks() {
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        ScreenerParams p = new ScreenerParams();
        p.setMode("stock_only");

        ScreenerResultDTO result = service(3).scan(p);
        assertThat(result.indexFunds()).isEmpty();
        assertThat(result.steadyStocks()).isNotEmpty();
    }

    // ================= 口径摘要自洽 =================

    @Test
    void vetoCountsPlusEnteredScoringEqualsScannedTotal() {
        List<StockRow> universe = qualifiedUniverse(20);
        // 掺入各类垃圾，验证剔除计数与自洽性
        universe.addAll(List.of(
                junk("600600", "ST退市股", r -> r.setName("ST退市股")),
                junk("600601", "银行股", r -> r.setIndustry("银行")),
                junk("600602", "亏损股", r -> r.setPeTtm(new BigDecimal("-3"))),
                junk("600603", "高杠杆", r -> r.setDebtRatio(new BigDecimal("92"))),
                junk("600604", "停牌", r -> r.setAmount(BigDecimal.ZERO)),
                junk("600605", "次新", r -> r.setListDate(LocalDate.now().minusMonths(3)))));
        when(client.fetchUniverse()).thenReturn(snapshot(universe));

        ScreenerResultDTO result = service(3).scan(ScreenerParams.defaults());
        ScreenerResultDTO.Summary s = result.summary();

        int counted = s.vetoCounts().stream().mapToInt(ScreeningRules.VetoCount::count).sum();
        assertThat(counted + s.afterVetoes()).isEqualTo(s.scanned());
        assertThat(s.scanned()).isEqualTo(universe.size());
        assertThat(s.vetoCounts()).isNotEmpty();
        // 金融地产被排除的数量必须是公开的
        assertThat(s.vetoCounts()).anyMatch(v -> v.rule().equals("V11") && v.count() >= 1);
        // 对不上时才会出现的告警，这里不该出现
        assertThat(s.degradations()).noneMatch(d -> d.contains("口径摘要对不上"));
    }

    private interface Mutator {
        void apply(StockRow row);
    }

    private static StockRow junk(String code, String name, Mutator mutator) {
        StockRow r = qualifiedUniverse(1).get(0);
        r.setCode(code);
        r.setName(name);
        mutator.apply(r);
        return r;
    }

    @Test
    void resultAlwaysCarriesTheDisclaimerAndDataTime() {
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(10)));
        ScreenerResultDTO result = service(3).scan(null);

        assertThat(result.disclaimer()).contains("不构成投资建议");
        assertThat(result.dataTime()).isNotBlank();
        assertThat(result.appliedParams().getMode()).isEqualTo("index_first");
    }

    @Test
    void illegalParamsAreRejectedBeforeAnyOutboundCall() {
        assertThatThrownBy(() -> service(3).scan(paramsWithBadPerBucket()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(client, never()).fetchUniverse();
    }

    private static ScreenerParams paramsWithBadPerBucket() {
        ScreenerParams p = new ScreenerParams();
        p.setPerBucket(9);
        return p;
    }

    // ================= 限流冷却 =================

    @Test
    void rateLimitedUniverseEntersCooldownAndTheNextScanDoesNotCallOutAgain() {
        when(client.fetchUniverse()).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("东财正在限流这个 IP", null));
        ScreenerCache cache = new ScreenerCache(15, 20, 10);
        StockScreenerService svc = service(3, cache);

        assertThatThrownBy(() -> svc.scan(ScreenerParams.defaults()))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
        assertThat(cache.inCooldown()).isTrue();

        // 第二次点击**必须不再外呼**：被限流时每一次重试都在把封禁推得更深
        assertThatThrownBy(() -> svc.scan(ScreenerParams.defaults()))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .hasMessageContaining("限流");
        verify(client, times(1)).fetchUniverse();
    }

    @Test
    void cooldownStillServesTheStaleSnapshotButSaysItIsStale() {
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        // TTL 0 → 手上那份立刻算过期，逼出「冷却期怎么办」这条分支
        ScreenerCache cache = new ScreenerCache(0, 20, 10);
        StockScreenerService svc = service(3, cache);

        svc.scan(ScreenerParams.defaults());
        cache.enterCooldown();

        ScreenerResultDTO second = svc.scan(ScreenerParams.defaults());

        // 有旧快照就给旧快照，但必须让人看出它是旧的
        assertThat(second.steadyStocks()).isNotEmpty();
        assertThat(second.summary().degradations())
                .anyMatch(d -> d.contains("限流") && d.contains("可能已经变化"));
        verify(client, times(1)).fetchUniverse();
    }

    @Test
    void throttledKlinesStartCooldownAndStillReturnResults() {
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(client.fetchKline(anyString())).thenReturn(
                MarketDataClient.KlineOutcome.throttled("行情源限流，价格位置未能更新"));
        ScreenerCache cache = new ScreenerCache(15, 20, 10);

        ScreenerResultDTO result = service(3, cache).scan(ScreenerParams.defaults());

        assertThat(result.steadyStocks()).isNotEmpty();   // 日线拿不到也不淘汰
        assertThat(cache.inCooldown()).isTrue();
        assertThat(result.summary().degradations()).anyMatch(d -> d.contains("限流"));
        // 「被限流」与「这只票的日线坏了」是两件事，不能混成一句
        assertThat(result.summary().degradations()).noneMatch(d -> d.contains("日线未取到"));
    }

    @Test
    void poolDescriptionSaysTheScanWasNotTheWholeMarket() {
        // 池子是「市值前 N 只」，这件事必须写进摘要。否则「扫描总数」会被当成全市场股票数，
        // 而它其实带着一个很高的市值下限。
        when(client.fetchUniverse()).thenReturn(new MarketDataClient.UniverseSnapshot(
                qualifiedUniverse(10), 500, 3, LocalDate.of(2026, 9, 30),
                new BigDecimal("38300000000")));

        ScreenerResultDTO result = service(3).scan(ScreenerParams.defaults());

        assertThat(result.summary().notes())
                .anyMatch(n -> n.contains("总市值前 500") && n.contains("383 亿"));
        assertThat(result.summary().degradations()).anyMatch(d -> d.contains("3 只"));
        assertThat(result.summary().notes()).anyMatch(n -> n.contains("2026-09-30"));
    }
}