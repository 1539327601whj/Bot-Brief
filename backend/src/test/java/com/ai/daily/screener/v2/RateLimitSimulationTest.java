package com.ai.daily.screener.v2;

import com.ai.daily.dto.Result;
import com.ai.daily.screener.AltQuoteSource;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.screener.MarketDataClient;
import com.ai.daily.screener.OffPoolIndexClient;
import com.ai.daily.screener.ScreenerCache;
import com.ai.daily.screener.StockRow;
import com.ai.daily.screener.StockValuationClient;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.screener.v2.ai.BriefV2Service;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.EtfPriceHistoryService;
import com.ai.daily.service.MarketValuationHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.InstrumentResolver;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P7：三类真实故障下，页面**要么可用、要么明确降级**。
 *
 * <p>这里不接网络，而是把「故障」放在源客户端的边界上，然后走真实的
 * {@link OnDemandSyncService} → {@link InstrumentFactsService} →
 * {@link CodeLookupV2Service} → controller 链路：
 *
 * <ol>
 *   <li><b>429 限流</b>：本地有数据 → 200 + 本地价 + 冷却提示；本地无数据 → 429，且全程只发一次请求；</li>
 *   <li><b>超时</b>：行情/日线/估值都超时 → 200，用本地陈旧数据并把每一格标成 PARTIAL；</li>
 *   <li><b>字段漂移</b>：源认得代码但没有报价字段 → 503「没有可用报价」，**不能**误报 404 让人去改代码。</li>
 * </ol>
 *
 * <p>所有断言都围绕同一件事：**没有数据时不能编 0**，且降级理由要能到达页面。
 */
class RateLimitSimulationTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.now(SHANGHAI);
    private static final String RAW = "600519";
    private static final String KEY = "STOCK:600519";

    private MarketDataClient marketDataClient;
    private AltQuoteSource altQuoteSource;
    private StockValuationClient stockValuationClient;
    private WarehouseRepository repository;
    private ScreenerCache cache;
    private InstrumentResolver resolver;
    private WarehouseSchemaGuard schemaGuard;
    private MarketValuationHistoryService valuationHistoryService;
    private IndexFundPool indexFundPool;
    private WarehouseProperties properties;
    private CodeLookupMetrics metrics;

    private OnDemandSyncService syncService;
    private CodeLookupV2Service service;
    private CodeLookupV2Controller controller;

    @BeforeEach
    void setUp() {
        marketDataClient = mock(MarketDataClient.class);
        altQuoteSource = mock(AltQuoteSource.class);
        stockValuationClient = mock(StockValuationClient.class);
        repository = mock(WarehouseRepository.class);
        cache = mock(ScreenerCache.class);
        resolver = mock(InstrumentResolver.class);
        schemaGuard = mock(WarehouseSchemaGuard.class);
        valuationHistoryService = mock(MarketValuationHistoryService.class);
        indexFundPool = mock(IndexFundPool.class);
        properties = new WarehouseProperties();
        metrics = new CodeLookupMetrics();

        when(resolver.resolve(anyString())).thenReturn(InstrumentCode.stock(RAW, "SH"));
        when(schemaGuard.isReady()).thenReturn(true);
        when(cache.inCooldown(anyString())).thenReturn(false);
        when(cache.cooldownRemainingMinutes(anyString())).thenReturn(5L);

        // 默认：另一侧完全空库。具体用例再按需覆盖。
        when(repository.findInstrument(anyString())).thenReturn(Optional.empty());
        when(repository.latestPrice(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.latestPrices(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(repository.latestPriceDate(anyString())).thenReturn(null);
        when(repository.countPrices(anyString())).thenReturn(0L);
        when(repository.latestValuation(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.valuationSeries(anyString(), anyString(), any())).thenReturn(List.of());
        when(repository.financialsAvailableAsOf(anyString(), any(), anyInt())).thenReturn(List.of());
        when(repository.moneyflow(anyString(), anyInt())).thenReturn(List.of());
        when(repository.events(anyString(), anyInt())).thenReturn(List.of());

        // 兜底源默认「问过了，没有这一行」——与真实空响应一致。
        when(altQuoteSource.fetchTencentQuotesBySymbols(anyList())).thenReturn(Map.of());
        when(altQuoteSource.fetchSinaQuotesBySymbols(anyList())).thenReturn(Map.of());
        when(marketDataClient.fetchKlineRows(anyString(), anyInt()))
                .thenReturn(MarketDataClient.KlineRowsOutcome.failed("日线源不可用"));
        when(stockValuationClient.fetchHistory(anyString()))
                .thenReturn(new StockValuationClient.History(RAW, null, List.of(), 0,
                        "估值源不可用"));

        syncService = new OnDemandSyncService(marketDataClient, altQuoteSource,
                stockValuationClient, repository, cache, properties, indexFundPool);
        InstrumentFactsService factsService = new InstrumentFactsService(resolver, syncService,
                repository, valuationHistoryService, indexFundPool,
                mock(EtfPriceHistoryService.class), mock(OffPoolIndexClient.class),
                mock(EtfLiveKlineService.class), mock(FundProfileClient.class), cache, properties);
        service = new CodeLookupV2Service(factsService, new InvestmentAnalysisService(),
                repository, schemaGuard, properties,
                mock(com.ai.daily.screener.TencentInstrumentSearchClient.class));
        controller = new CodeLookupV2Controller(service, mock(SeriesService.class),
                mock(BriefV2Service.class), metrics);

        authenticateAsAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // 1. 429 限流
    // ------------------------------------------------------------------

    @Test
    void rateLimitedButLocalWarehouseHasDataReturns200WithHonestDegradation() {
        stubFreshLocalWarehouse(TODAY);
        when(marketDataClient.fetchQuotes(anyList())).thenThrow(
                new com.ai.daily.screener.MarketDataException.MarketDataRateLimitedException("429"));

        Result<CodeLookupV2DTO.CoreView> result = controller.core(RAW);

        assertThat(result.getCode()).isEqualTo(200);
        CodeLookupV2DTO.CoreView core = result.getData();
        // 本地收盘价被如实标成「本地仓库」，不是源报价。
        assertThat(core.quote().provider()).isEqualTo("local");
        assertThat(core.quote().price()).isEqualByComparingTo("1500");
        assertThat(core.data().get("quote").status()).isEqualTo("PARTIAL");
        assertThat(core.retryAfterMs()).isPositive();
        // 限流原因必须留在「现价」这一格的说明里：只写「源没返回报价」会让人以为
        // 是源故障，而不是「冷却中，等一会儿再来」。
        assertThat(core.data().get("quote").note()).contains("冷却");
        assertThat(core.data().get("quote").note()).contains("本地最近收盘价");
        // 有完整本地数据时结论照常给出；限流只影响「现价」这一格。
        assertThat(core.verdict().score()).isNotNull();
        assertThat(core.verdict().withheldReason()).isNull();

        // 绝不重试：一次查询只发一次东财请求，然后进入冷却。
        verify(marketDataClient, times(1)).fetchQuotes(anyList());
        verify(cache).enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
        // 本地日线/估值是新鲜的，不该被限流带着一起重抓。
        verify(marketDataClient, never()).fetchKlineRows(anyString(), anyInt());
        verify(stockValuationClient, never()).fetchHistory(anyString());

        assertThat(metrics.snapshot().coreOk()).isEqualTo(1);
        assertThat(metrics.snapshot().cacheHits()).isEqualTo(1);
        assertThat(metrics.snapshot().rateLimited()).isEqualTo(1);
    }

    @Test
    void rateLimitedWithNoLocalDataReturns429AndStillOnlyOneCall() {
        when(marketDataClient.fetchQuotes(anyList())).thenThrow(
                new com.ai.daily.screener.MarketDataException.MarketDataRateLimitedException("429"));

        Result<CodeLookupV2DTO.CoreView> result = controller.core(RAW);

        assertThat(result.getCode()).isEqualTo(429);
        assertThat(result.getMessage()).contains("冷却");
        verify(marketDataClient, times(1)).fetchQuotes(anyList());
        verify(cache).enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
        // 失败的首屏不计入成功与缓存命中，但限流单独计数。
        assertThat(metrics.snapshot().coreOk()).isZero();
        assertThat(metrics.snapshot().rateLimited()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2. 超时
    // ------------------------------------------------------------------

    @Test
    void timeoutsOnEverySourceStillRenderThePageFromStaleLocalData() {
        LocalDate stalePriceDate = TODAY.minusDays(10);
        stubLocalWarehouse(stalePriceDate, TODAY.minusDays(30));
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new RuntimeException("Read timed out"));
        when(altQuoteSource.fetchTencentQuotesBySymbols(anyList()))
                .thenThrow(new RuntimeException("Read timed out"));
        when(altQuoteSource.fetchSinaQuotesBySymbols(anyList()))
                .thenThrow(new RuntimeException("Read timed out"));
        when(marketDataClient.fetchKlineRows(anyString(), anyInt()))
                .thenReturn(MarketDataClient.KlineRowsOutcome.failed(
                        "日线不可达（Read timed out），价格位置未确认"));
        when(stockValuationClient.fetchHistory(anyString()))
                .thenThrow(new RuntimeException("Read timed out"));

        Result<CodeLookupV2DTO.CoreView> result = controller.core(RAW);

        assertThat(result.getCode()).isEqualTo(200);
        CodeLookupV2DTO.CoreView core = result.getData();
        assertThat(core.quote().provider()).isEqualTo("local");
        assertThat(core.data().get("quote").status()).isEqualTo("PARTIAL");
        assertThat(core.data().get("prices").status()).isEqualTo("PARTIAL");
        assertThat(core.data().get("valuations").status()).isEqualTo("PARTIAL");
        assertThat(core.data().get("prices").note()).contains("沿用本地日线");
        assertThat(core.data().get("valuations").note()).contains("不可用");
        // 价格落后超过 5 个交易日时**不给结论**，而不是拿旧价硬算一个分数。
        assertThat(core.verdict().score()).isNull();
        assertThat(core.verdict().withheldReason()).contains("陈旧");
        verify(marketDataClient, times(1)).fetchQuotes(anyList());
    }

    // ------------------------------------------------------------------
    // 3. 字段漂移
    // ------------------------------------------------------------------

    @Test
    void recognizedCodeWithoutUsableQuoteIs503Not404() {
        StockRow drifted = new StockRow();
        drifted.setCode(RAW);
        drifted.setName("贵州茅台");
        drifted.setIndustry("酿酒行业");
        // 价格字段缺失：源换了字段名时的典型表现。
        drifted.setPrice(null);
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(Map.of(RAW, drifted));
        when(marketDataClient.fetchKlineRows(anyString(), anyInt()))
                .thenReturn(MarketDataClient.KlineRowsOutcome.failed(
                        "日线返回结构异常，价格位置未确认"));

        Result<CodeLookupV2DTO.CoreView> result = controller.core(RAW);

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).contains("没有可用报价");
        assertThat(result.getMessage()).doesNotContain("查不到代码");
        verify(marketDataClient, times(1)).fetchQuotes(anyList());
    }

    @Test
    void driftedDailyRowsFallBackToLocalBarsInsteadOfFabricating() {
        stubLocalWarehouse(TODAY.minusDays(10), TODAY);
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(Map.of(RAW, quoteRow("1520")));
        when(marketDataClient.fetchKlineRows(anyString(), anyInt()))
                .thenReturn(MarketDataClient.KlineRowsOutcome.failed(
                        "日线无有效收盘价，价格位置未确认"));

        Result<CodeLookupV2DTO.CoreView> result = controller.core(RAW);

        assertThat(result.getCode()).isEqualTo(200);
        CodeLookupV2DTO.CoreView core = result.getData();
        // 现价来自本轮的源报价，日线仍是本地旧数据；两者不混成一条序列。
        assertThat(core.quote().price()).isEqualByComparingTo("1520");
        assertThat(core.data().get("prices").status()).isEqualTo("PARTIAL");
        assertThat(core.data().get("prices").note()).contains("无有效收盘价");
        assertThat(core.technical().barCount()).isEqualTo(300);
        assertThat(core.technical().price()).isNotNull();
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void stubFreshLocalWarehouse(LocalDate priceAsOf) {
        stubLocalWarehouse(priceAsOf, TODAY);
    }

    private void stubLocalWarehouse(LocalDate priceAsOf, LocalDate valuationAsOf) {
        List<WarehouseRows.PriceBar> bars = bars(priceAsOf, 300);
        List<WarehouseRows.ValuationPoint> valuations = valuations(valuationAsOf);

        when(repository.latestPrices(eq(KEY), eq(OnDemandSyncService.ADJ_QFQ), anyInt()))
                .thenReturn(bars);
        when(repository.latestPrice(eq(KEY), eq(OnDemandSyncService.ADJ_QFQ)))
                .thenReturn(Optional.of(bars.get(bars.size() - 1)));
        when(repository.latestPriceDate(KEY)).thenReturn(priceAsOf.toString());
        when(repository.countPrices(KEY)).thenReturn((long) bars.size());
        when(repository.latestValuation(eq(KEY), eq(OnDemandSyncService.VALUATION_SOURCE)))
                .thenReturn(Optional.of(valuations.get(valuations.size() - 1)));
        when(repository.valuationSeries(eq(KEY), eq(OnDemandSyncService.VALUATION_SOURCE), any()))
                .thenReturn(valuations);
        when(repository.financialsAvailableAsOf(eq(RAW), any(), anyInt()))
                .thenReturn(reports());
        when(repository.moneyflow(eq(RAW), anyInt())).thenReturn(flows(priceAsOf));
    }

    private static StockRow quoteRow(String price) {
        StockRow row = new StockRow();
        row.setCode(RAW);
        row.setName("贵州茅台");
        row.setIndustry("酿酒行业");
        row.setPrice(new BigDecimal(price));
        row.setPctChange(new BigDecimal("1.2"));
        row.setAmount(new BigDecimal("2000000000"));
        row.setTurnoverRate(new BigDecimal("0.5"));
        row.setTotalMarketCap(new BigDecimal("100000000000"));
        row.setPeTtm(new BigDecimal("20"));
        row.setPb(new BigDecimal("8"));
        row.setListDate(LocalDate.of(2001, 8, 27));
        return row;
    }

    /** 300 根日线，收盘价从 1200 线性升到 1500（最后一根固定 1500）。 */
    private static List<WarehouseRows.PriceBar> bars(LocalDate endDate, int count) {
        List<LocalDate> dates = tradingDays(endDate, count);
        List<WarehouseRows.PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal close = new BigDecimal("1200")
                    .add(new BigDecimal("300")
                            .multiply(BigDecimal.valueOf(i))
                            .divide(BigDecimal.valueOf(count - 1), 4, java.math.RoundingMode.HALF_UP));
            bars.add(new WarehouseRows.PriceBar(KEY, dates.get(i), OnDemandSyncService.ADJ_QFQ,
                    close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close,
                    BigDecimal.valueOf(1_000_000), new BigDecimal("1500000000"),
                    new BigDecimal("0.5"), new BigDecimal("0.4"),
                    OnDemandSyncService.PRICE_SOURCE, LocalDateTime.now(SHANGHAI)));
        }
        return bars;
    }

    /** 946 个估值点（跨度 5 年多），PE 在 10–20 之间循环，最后一点固定 20。 */
    private static List<WarehouseRows.ValuationPoint> valuations(LocalDate endDate) {
        int count = 946;
        List<WarehouseRows.ValuationPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int slot = i % 100;
            BigDecimal pe = new BigDecimal("10").add(new BigDecimal("10")
                    .multiply(BigDecimal.valueOf(slot))
                    .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
            BigDecimal pb = new BigDecimal("1").add(new BigDecimal("2")
                    .multiply(BigDecimal.valueOf(slot))
                    .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
            if (i == count - 1) {
                pe = new BigDecimal("20");
            }
            points.add(new WarehouseRows.ValuationPoint(KEY,
                    endDate.minusDays(2L * (count - 1 - i)), pe, pb, null,
                    new BigDecimal("1.5"), null, null,
                    OnDemandSyncService.VALUATION_SOURCE, LocalDateTime.now(SHANGHAI)));
        }
        return points;
    }

    private static List<WarehouseRows.FinancialReport> reports() {
        List<WarehouseRows.FinancialReport> rows = new ArrayList<>();
        rows.add(report("2026-06-30", new BigDecimal("5.0"), new BigDecimal("18"),
                new BigDecimal("0.80"), new BigDecimal("22")));
        rows.add(report("2025-12-31", new BigDecimal("4.0"), new BigDecimal("16"),
                new BigDecimal("0.60"), new BigDecimal("20")));
        rows.add(report("2024-12-31", new BigDecimal("3.2"), new BigDecimal("12"),
                new BigDecimal("0.45"), new BigDecimal("15")));
        rows.add(report("2022-12-31", new BigDecimal("2.2"), new BigDecimal("8"),
                new BigDecimal("0.30"), new BigDecimal("10")));
        return rows;
    }

    private static WarehouseRows.FinancialReport report(String date, BigDecimal revenueYi,
                                                        BigDecimal revenueYoy,
                                                        BigDecimal profitYi,
                                                        BigDecimal profitYoy) {
        BigDecimal yi = BigDecimal.valueOf(100_000_000L);
        BigDecimal revenue = revenueYi.multiply(yi);
        BigDecimal profit = profitYi.multiply(yi);
        LocalDate reportDate = LocalDate.parse(date);
        return new WarehouseRows.FinancialReport(RAW, reportDate, "ANNUAL",
                reportDate.plusDays(40), reportDate.plusDays(40),
                revenue, revenueYoy, profit, profitYoy,
                new BigDecimal("14"), new BigDecimal("35"), new BigDecimal("16"),
                profit.multiply(new BigDecimal("1.1")),
                new BigDecimal("120").multiply(yi), new BigDecimal("60").multiply(yi),
                new BigDecimal("60").multiply(yi), new BigDecimal("50").multiply(yi),
                new BigDecimal("30").multiply(yi), new BigDecimal("0.5").multiply(yi),
                new BigDecimal("10").multiply(yi), new BigDecimal("2").multiply(yi),
                new BigDecimal("0.8").multiply(yi), "eastmoney", LocalDateTime.now(SHANGHAI));
    }

    private static List<WarehouseRows.MoneyFlow> flows(LocalDate endDate) {
        List<LocalDate> dates = tradingDays(endDate, 30);
        List<WarehouseRows.MoneyFlow> rows = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            rows.add(new WarehouseRows.MoneyFlow(RAW, date, new BigDecimal("20000000"),
                    null, null, null, null, new BigDecimal("1.2"),
                    "eastmoney", LocalDateTime.now(SHANGHAI)));
        }
        return rows;
    }

    private static List<LocalDate> tradingDays(LocalDate end, int count) {
        List<LocalDate> dates = new ArrayList<>(count);
        LocalDate cursor = end;
        while (dates.size() < count) {
            DayOfWeek dow = cursor.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                dates.add(0, cursor);
            }
            cursor = cursor.minusDays(1);
        }
        return dates;
    }

    private static void authenticateAsAdmin() {
        UserPrincipal up = new UserPrincipal(1L, "admin@example.com", "ADMIN", "USER", "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(up, null, up.getAuthorities()));
    }
}
