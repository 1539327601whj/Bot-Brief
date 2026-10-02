package com.ai.daily.screener;

import com.ai.daily.entity.EtfPriceHistory;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.EtfPriceHistoryService;
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
    private EtfPriceHistoryService priceService;
    private IndexFundPool indexPool;
    private AltQuoteSource alt;
    private ScreenerPrefetchService prefetchService;

    @BeforeEach
    void setUp() {
        client = mock(MarketDataClient.class);
        valuationService = mock(MarketValuationHistoryService.class);
        priceService = mock(EtfPriceHistoryService.class);
        alt = mock(AltQuoteSource.class);
        // 本地预取库默认**没有今天的快照**（Mockito 对 Optional 返回 empty）。
        // 于是这个类里既有断言（外呼次数、缓存、冷却、指数块）全部保持原语义，
        // 且**与跑测试的时刻无关**——盘后跑也一样走实时那条链。想测预取读侧的用例
        // 在 StockScreenerServicePrefetchReadTest 里，那里的时间是可注入的。
        prefetchService = mock(ScreenerPrefetchService.class);
        // 用**生产那份**池子，顺带证明它在真实 classpath 上解析得过
        indexPool = new IndexFundPool(
                new org.springframework.core.io.ClassPathResource("screener/index-pool.json"));
        when(client.fetchEtfQuotes(any())).thenReturn(Map.of());
        when(client.fetchKline(anyString())).thenReturn(klineOk());
        when(client.klineLimit()).thenReturn(250);
        // 本地预取库默认**是空的**：既有测试断言的仍然全是「外呼那条链」的行为，
        // 想测本地库那层的用例自己覆盖这条 stub。
        // （Mockito 对 List 返回值本来就给空列表，写出来是为了让「默认走外呼」这件事看得见，
        // 而不是因为它不写会 NPE。）
        when(priceService.latestBatch(any(), any(), any())).thenReturn(List.of());
        // 兜底源默认「什么都没补到」：不写这条的话 mock 会返回 null，
        // 于是每条测试都在测一个 UnsupportedOperationException 之外的假象
        when(alt.fetchTencentQuotes(any())).thenReturn(Map.of());
        when(alt.fetchSinaQuotes(any())).thenReturn(Map.of());
        when(alt.fetchTencentKline(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("腾讯日线为空", AltQuoteSource.PROVIDER_TENCENT));
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static MarketDataClient.KlineOutcome klineOk() {
        return MarketDataClient.KlineOutcome.ok(bars());
    }

    private static List<PricePositionCalculator.Bar> bars() {
        return bars(250);
    }

    private static List<PricePositionCalculator.Bar> bars(int count) {
        List<PricePositionCalculator.Bar> bars = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(count + 50);
        for (int i = 0; i < count; i++) {
            // 缓步下行：贴着近一年低位，正是「价格位置」这一维想要的形态
            bars.add(new PricePositionCalculator.Bar(start.plusDays(i),
                    BigDecimal.valueOf(20 - i * 0.03)));
        }
        return bars;
    }

    /**
     * 一条指数 ETF 行情，单位与东财一致（元）。
     *
     * <p>{@code provider} 决定字段全不全：**新浪那一路是真的没有规模**，
     * 夹具必须跟着它少那几个字段，否则「规模为空并写明原因」这条根本测不到。
     */
    private static MarketDataClient.EtfQuote quote(String code, String provider) {
        boolean sina = AltQuoteSource.PROVIDER_SINA.equals(provider);
        return new MarketDataClient.EtfQuote(code, code + "ETF", new BigDecimal("4.432"),
                new BigDecimal("0.36"),
                new BigDecimal(sina ? "2196229027" : "2196230000"),
                sina ? null : new BigDecimal("106898000000"),
                null, null, provider);
    }

    /** 七只指数 ETF 全部由某个源提供。 */
    private Map<String, MarketDataClient.EtfQuote> allFrom(String provider) {
        Map<String, MarketDataClient.EtfQuote> m = new java.util.LinkedHashMap<>();
        for (IndexFundPool.Fund f : indexPool.withEtf()) m.put(f.etfCode(), quote(f.etfCode(), provider));
        return m;
    }

    private static List<String> poolEtfCodes(IndexFundPool pool) {
        return pool.withEtf().stream().map(IndexFundPool.Fund::etfCode).toList();
    }

    private StockScreenerService service(int fetchThreads) {
        return service(fetchThreads, new ScreenerCache(15, 20, 60, 10));
    }

    /** 让需要自己拿住缓存的测试（冷却那几条）能注入实例。 */
    private StockScreenerService service(int fetchThreads, ScreenerCache cache) {
        return service(fetchThreads, cache, 10);
    }

    /**
     * 兜底额度也做成参数：额度用尽时的行为（说清楚少了几只）本身要有测试盯着。
     *
     * <p>预取读侧的开关恒为 {@code true}——即生产默认值。走到实时那条链靠的是
     * {@code prefetchService} 这个 mock 说「库里没有今天的快照」，而不是靠把开关关掉：
     * 关掉开关去测，等于绕开了真正会出事的那条判据。
     */
    private StockScreenerService service(int fetchThreads, ScreenerCache cache, int maxLiveFetches) {
        return new StockScreenerService(client, cache, valuationService, priceService, indexPool, alt,
                prefetchService, true, fetchThreads, maxLiveFetches);
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
        assertThat(result.indexFunds()).hasSize(indexPool.funds().size());
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
        when(valuationService.latestForIndices(any())).thenReturn(Map.of("SH000300", row));

        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");
        ScreenerResultDTO result = service(3).scan(p);

        ScreenerResultDTO.IndexFundItem hs300 = item(result, "510300");
        assertThat(hs300.pePercentile()).isEqualByComparingTo("38.5");
        // 有值 ⇒ 状态串必须为 null。两样都给会退化成「显示哪个」的模糊地带
        assertThat(hs300.percentileStatus()).isNull();
        assertThat(hs300.peStatus()).isNull();
        assertThat(hs300.valuationTradeDate()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(hs300.notes()).anyMatch(n -> n.contains("不可横向比较"));

        // 池子里每个指数都钉了估值来源，但**库里不一定已经有那一行**——每日同步还没跑到，
        // 或者这个指数今天刚加进池子。这种空缺同样要说清「为什么缺 + 下一步怎样」，
        // 而且**不能是破折号**——破折号正是用户截图里抱怨的那一片空白
        ScreenerResultDTO.IndexFundItem other = item(result, "588000");
        assertThat(other.pePercentile()).isNull();
        assertThat(other.peTtm()).isNull();
        assertThat(other.percentileStatus())
                .contains("库里暂无").contains("16:30").doesNotContain("—");
        // 缺口归缺口，来源与口径照旧印出来——读者要能自己判断「这是该去补哪一处的数」
        assertThat(other.valuationSource()).isEqualTo("中证指数官网");
        assertThat(other.percentileMethod()).isEqualTo("CSI_PE_TTM_ROLLING_10Y");
    }

    /**
     * 池子里某个指数**根本没接**估值源时的样子。
     *
     * <p>生产池子现在每个指数都接了源，所以这条分支只能靠一份合成池子测——但分支本身要留着：
     * 用户那句「好多 PE 和 PE 分位都没有」的正确答案就是它（没接就要说清没接，
     * 而不是给一个空格子或者一个 0）。
     */
    @Test
    void anIndexWithNoValuationSourceSaysSoInsteadOfShowingABlank() {
        IndexFundPool synthetic = new IndexFundPool(new org.springframework.core.io.ByteArrayResource("""
                {"indices":[{"indexCode":"SH000300","indexName":"沪深300","category":"broad",
                 "etfCode":"510300","market":1,"etfName":"沪深300ETF","tracking":"沪深300"}]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = new StockScreenerService(client, new ScreenerCache(15, 20, 60, 10),
                valuationService, priceService, synthetic, alt, prefetchService, true, 3, 10).scan(p);

        ScreenerResultDTO.IndexFundItem f = item(result, "510300");
        assertThat(f.valuationSource()).isNull();
        assertThat(f.percentileMethod()).isNull();
        assertThat(f.percentileStatus())
                .contains("未接入 PE 历史分位数据源")
                .contains("不对其估值高低作任何判断")
                .doesNotContain("—");
        assertThat(f.peStatus()).contains("未接入 PE 数据源");
        // 没接估值源不该牵连行情与价格位置：三块数据来源互相独立
        assertThat(f.pricePercentile()).isNotNull();
        assertThat(f.positionStatus()).isNull();
    }

    /**
     * 「不留空」的机械证明：逐格核对 {@code xStatus != null ⟺ 对应数值 == null}。
     *
     * <p>这条是唯一能证明「用户不再看到破折号」的办法——靠肉眼看页面会漏，
     * 而这条规则一旦在某一格上破口（有值却有状态串、或者没值却没状态串），
     * 前端就会渲染出一个空span或一句错话。
     */
    @Test
    void everyMissingNumberHasAReasonAndEveryReasonMeansMissing() {
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        // **一次默认扫描是不够的。** 默认场景里行情整批没取到，所有格子走的是同一条
        // 「没取到行情」的分支，于是那些**逐字段**的分支（兜底源字段不全、腾讯字段位不可信、
        // 日线不足 250 根）根本没被执行到——而空白恰恰是从那里漏出来的。
        // 所以这里把「能造出空白的每一种取数结果」都跑一遍，每张卡都核对。
        List<String> names = new ArrayList<>();
        List<ScreenerResultDTO> scenarios = new ArrayList<>();

        names.add("行情整批没取到");
        scenarios.add(service(3).scan(p));

        names.add("行情来自新浪兜底源（它真的不给规模）");
        when(alt.fetchSinaQuotes(any())).thenReturn(allFrom(AltQuoteSource.PROVIDER_SINA));
        scenarios.add(service(3).scan(p));

        names.add("行情来自腾讯兜底源（字段全）");
        when(alt.fetchTencentQuotes(any())).thenReturn(allFrom(AltQuoteSource.PROVIDER_TENCENT));
        scenarios.add(service(3).scan(p));

        names.add("日线只有 200 根（MA250 算不出来）");
        when(client.fetchKline(anyString())).thenReturn(MarketDataClient.KlineOutcome.ok(bars(200)));
        scenarios.add(service(3).scan(p));

        names.add("日线取自本地预取库");
        stubDb(poolEtfCodes(indexPool).toArray(String[]::new));
        scenarios.add(service(3).scan(p));

        for (int i = 0; i < scenarios.size(); i++) {
            assertNoBlanks(names.get(i), scenarios.get(i));
        }
    }

    /** 逐格核对 {@code xStatus != null ⟺ 对应数值 == null}，并带上场景名，红了才知道是哪一种。 */
    private static void assertNoBlanks(String scenario, ScreenerResultDTO result) {
        assertThat(result.indexFunds()).as(scenario + "：指数块不该是空的").isNotEmpty();
        assertThat(result.indexFunds()).allSatisfy(f -> {
            // 用 List 而不是 Map 装：几个状态串天然会**相等**（例如「没有代表 ETF」那一句
            // 同时管四格），用 Map 会把它们折成一条，于是四格里只检查到一格。
            for (Object[] pair : pairs(f)) {
                String status = (String) pair[0];
                boolean anyMissing = false;
                for (int i = 1; i < pair.length; i++) {
                    if (pair[i] == null) anyMissing = true;
                }
                String where = scenario + " / " + f.etfCode() + " 的 " + pair[1];
                if (status == null) {
                    assertThat(anyMissing).as(where + "：有数值缺失却没有状态串").isFalse();
                } else {
                    assertThat(status).as(where + "：状态串不能是破折号占位").isNotEqualTo("—");
                    assertThat(status.trim()).as(where + "：状态串要说清为什么缺").isNotEmpty();
                    assertThat(anyMissing)
                            .as(where + "：状态串非 null，但对应的数值一个都不缺").isTrue();
                }
            }
        });
    }

    /**
     * 状态串 → 它负责的那几格。**顺序与 {@link ScreenerResultDTO.IndexFundItem} 的声明一致**，
     * 加一格数值就要在这里加一格，否则那条「不留空」的证明会悄悄少检查一项。
     */
    private static List<Object[]> pairs(ScreenerResultDTO.IndexFundItem f) {
        List<Object[]> pairs = new ArrayList<>();
        pairs.add(new Object[]{f.priceStatus(), "price", f.price()});
        pairs.add(new Object[]{f.pctChangeStatus(), "pctChange", f.pctChange()});
        pairs.add(new Object[]{f.amountStatus(), "amountYi", f.amountYi()});
        pairs.add(new Object[]{f.scaleStatus(), "scaleYi", f.scaleYi()});
        pairs.add(new Object[]{f.positionStatus(), "价格位置",
                f.pricePercentile(), f.drawdownFromHigh(), f.maxDrawdownInYear(),
                f.annualizedVolatility(), f.vsMa250(), f.barCount(), f.lastTradeDate()});
        pairs.add(new Object[]{f.peStatus(), "peTtm", f.peTtm()});
        pairs.add(new Object[]{f.percentileStatus(), "pePercentile", f.pePercentile()});
        return pairs;
    }

    /**
     * 日线只有 200 根时的样子：**只有「对 MA250」那一格没有值**，其余四格照常有值。
     *
     * <p>这条防的是一类很容易写出来的错误——把价格位置当成「全有或全无」，
     * 于是一块缺一格就让整块显红；或者反过来，status 只在一格上非 null 就把它当成全块缺失。
     * 两种情况在页面上都表现为「明明有数的地方也是红的」或者「该红的地方是空的」。
     */
    @Test
    void aShortSeriesLeavesOnlyTheMa250CellWithoutAValue() {
        List<PricePositionCalculator.Bar> short_ = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(240);
        for (int i = 0; i < 200; i++) {
            short_.add(new PricePositionCalculator.Bar(start.plusDays(i),
                    BigDecimal.valueOf(20 - i * 0.03)));
        }
        when(client.fetchKline(anyString())).thenReturn(MarketDataClient.KlineOutcome.ok(short_));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO.IndexFundItem f = item(service(3).scan(p), "510300");

        assertThat(f.barCount()).isEqualTo(200);
        assertThat(f.vsMa250()).isNull();
        assertThat(f.pricePercentile()).isNotNull();
        assertThat(f.annualizedVolatility()).isNotNull();
        // 状态串只说那一格为什么没有，不牵连其余四格
        assertThat(f.positionStatus()).contains("MA250");
        assertThat(f.lastTradeDate()).isNotNull();
    }

    @Test
    void theCardSaysWhereEachNumberCameFrom() {
        stubDb(poolEtfCodes(indexPool).toArray(String[]::new));
        when(alt.fetchTencentQuotes(any())).thenReturn(allFrom(AltQuoteSource.PROVIDER_TENCENT));
        MarketValuationHistory row = new MarketValuationHistory();
        row.setIndexCode("SH000300");
        row.setPeTtm(new BigDecimal("12.34"));
        row.setPePercentile(new BigDecimal("38.5"));
        row.setPercentileMethod("CSI_PE_TTM_ROLLING_10Y");
        row.setTradeDate(LocalDate.now().minusDays(1));
        when(valuationService.latestForIndices(any())).thenReturn(Map.of("SH000300", row));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO.IndexFundItem f = item(service(3).scan(p), "510300");

        // 「这个数是哪来的」是判断可信度的唯一依据——三块数据各有各的来源，都要印出来
        assertThat(f.quoteSource()).isEqualTo("腾讯（兜底）");
        assertThat(f.positionSource()).isEqualTo("本地预取库");
        assertThat(f.valuationSource()).isEqualTo("中证指数官网");
        assertThat(f.percentileMethod()).isEqualTo("CSI_PE_TTM_ROLLING_10Y");
        assertThat(f.lastTradeDate()).isEqualTo(LocalDate.now());
        assertThat(f.category()).isEqualTo("broad");
        assertThat(f.indexCode()).isEqualTo("SH000300");
        assertThat(f.indexName()).isEqualTo("沪深300");
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

    // ================= 本地预取库（阶段 5） =================

    /** 本地库的一行。默认收于指定日期，别让用例无意中掉进「日线陈旧」那条提示。 */
    private static EtfPriceHistory dbRow(String code, String source, LocalDate date, double close) {
        EtfPriceHistory h = new EtfPriceHistory();
        h.setFundCode(code);
        h.setFundName(code + "ETF");
        h.setTradeDate(date);
        h.setOpen(BigDecimal.valueOf(close));
        h.setHigh(BigDecimal.valueOf(close));
        h.setLow(BigDecimal.valueOf(close));
        h.setClose(BigDecimal.valueOf(close));
        h.setAdjustmentType(EtfPriceHistoryService.QFQ);
        h.setSource(source);
        h.setFetchedAt(java.time.LocalDateTime.now());
        return h;
    }

    /** 逐日递增的一条序列，**收于今天**（否则会触发「日线陈旧」提示，干扰断言）。 */
    private static List<EtfPriceHistory> dbSeries(String code, String source, int count, double first) {
        List<EtfPriceHistory> rows = new ArrayList<>();
        LocalDate end = LocalDate.now();
        for (int i = 0; i < count; i++) {
            rows.add(dbRow(code, source, end.minusDays(count - 1 - i), first + i * 0.01));
        }
        return rows;
    }

    /** 库存里有日线的那些代码。没列进去的会落到外呼那条链上。 */
    private void stubDb(String... codesWithBars) {
        List<EtfPriceHistory> rows = new ArrayList<>();
        for (String code : codesWithBars) rows.addAll(dbSeries(code, "eastmoney", 250, 3.5));
        when(priceService.latestBatch(any(), any(), any())).thenReturn(rows);
    }

    /** 按 ETF 代码取一张指数卡。池子里单位是指数，但测试里用 ETF 代码定位最省事。 */
    private static ScreenerResultDTO.IndexFundItem item(ScreenerResultDTO r, String etfCode) {
        return r.indexFunds().stream().filter(f -> etfCode.equals(f.etfCode()))
                .findFirst().orElseThrow();
    }

    @Test
    void indexBarsComeFromTheLocalLibraryWithoutASingleOutboundCall() {
        // 池子里 7 只 ETF 全都在库里 → 点击时**一次外呼都不该有**。
        // 这条正是「200 条池子跑得动」的全部依据。
        stubDb(poolEtfCodes(indexPool).toArray(String[]::new));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        assertThat(callsTo("fetchKline")).isZero();
        ScreenerResultDTO.IndexFundItem hs300 = item(result, "510300");
        assertThat(hs300.barCount()).isEqualTo(250);
        assertThat(hs300.pricePercentile()).isNotNull();
        assertThat(result.summary().notes()).anyMatch(n -> n.contains("本地预取库"));
    }

    @Test
    void aMixedSourceSeriesIsNotStitchedIntoOneCurve() {
        // 库里同一天同时有东财行与腾讯行（唯一键含 source），这里让两个源的**价位不同**，
        // 于是「选的是哪一条」在结果里可观测：混源会立刻改变 barCount 与回撤。
        List<EtfPriceHistory> rows = new ArrayList<>();
        LocalDate end = LocalDate.now();
        // 东财：100 根，停在 5 天前，价位 10.x（与腾讯**日期重叠**，所以同一天有两行）
        for (int i = 0; i < 100; i++) {
            rows.add(dbRow("510300", "eastmoney", end.minusDays(104 - i), 10 + i * 0.01));
        }
        // 腾讯：250 根，到今天，价位 20.x
        List<EtfPriceHistory> txRows = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            txRows.add(dbRow("510300", "tencent", end.minusDays(249 - i), 20 + i * 0.01));
        }
        rows.addAll(txRows);
        when(priceService.latestBatch(any(), any(), any())).thenReturn(rows);

        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");
        ScreenerResultDTO.IndexFundItem hs300 = item(service(3).scan(p), "510300");

        // 期望值由「只用腾讯那 250 根」独立算一遍得来——混源的曲线给不出这个数
        List<PricePositionCalculator.Bar> txBars = txRows.stream()
                .map(r -> new PricePositionCalculator.Bar(r.getTradeDate(), r.getClose()))
                .toList();
        PricePosition expected = PricePositionCalculator.compute(txBars);

        assertThat(hs300.barCount()).isEqualTo(250);
        assertThat(hs300.pricePercentile()).isEqualByComparingTo(expected.getPricePercentile());
        assertThat(hs300.maxDrawdownInYear()).isEqualByComparingTo(expected.getMaxDrawdownInYear());
        // 250 根才够算 MA250：用太少的话两边都是 null，这条断言会变成「null == null」的空检查
        assertThat(expected.getVsMa250()).isNotNull();
        assertThat(hs300.vsMa250()).isEqualByComparingTo(expected.getVsMa250());
    }

    @Test
    void aBrokenLocalLibraryFallsBackToTheOutboundChainInsteadOfEmptyingPricePositions() {
        when(priceService.latestBatch(any(), any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO.IndexFundItem hs300 = item(service(3).scan(p), "510300");

        // 库连不上**不等于**没有日线：该退回外呼那条链，而不是让价格位置整块变空
        assertThat(callsTo("fetchKline")).isPositive();
        assertThat(hs300.pricePercentile()).isNotNull();
        assertThat(hs300.barCount()).isNotNull();
    }

    @Test
    void theWholePoolsValuationsAreReadInOneQuery() {
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        service(3).scan(p);

        // 逐条 latest() 在 200 条池子上就是 200 次往返
        verify(valuationService, times(1)).latestForIndices(any());
        verify(valuationService, never()).latest(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aFailedValuationReadIsNotReportedAsThereBeingNoData() {
        when(valuationService.latestForIndices(any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        // 两种「估值是空的」必须分开说：这次是**查询没成功**，不是「库里没有这个指数」
        assertThat(result.summary().degradations())
                .anyMatch(d -> d.contains("查询没成功"));
        assertThat(item(result, "510300").pePercentile()).isNull();
        // 而且不能顺手把这句假的写进卡片
        assertThat(result.summary().degradations()).noneMatch(d -> d.contains("库里暂无"));
    }

    @Test
    void aSuccessfulValuationReadAddsNoDegradation() {
        // 反证上一条：正常读数时不该出现那条降级提示，否则它就成了噪音，没人会再读它
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        assertThat(result.summary().degradations()).noneMatch(d -> d.contains("查询没成功"));
    }

    @Test
    void theValuationCacheStopsTheSecondClickFromQueryingAgain() {
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);
        StockScreenerService svc = service(3, cache);
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        svc.scan(p);
        svc.scan(p);

        // 估值一天才变一次，一小时 TTL 内不该再查一次库
        verify(valuationService, times(1)).latestForIndices(any());
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
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);
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
        ScreenerCache cache = new ScreenerCache(0, 20, 60, 10);
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
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);

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

    // ================= 指数行情兜底链：东财 → 腾讯 → 新浪 =================

    @Test
    void whenEastmoneyReturnsNothingTheIndexQuotesComeFromTencent() {
        // 主源整批空手（限流、接口改版、或者只是这一批没回）时，指数块不该是一片空白
        when(alt.fetchTencentQuotes(any())).thenReturn(allFrom(AltQuoteSource.PROVIDER_TENCENT));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        assertThat(result.indexFunds()).isNotEmpty();
        assertThat(result.indexFunds()).allSatisfy(f -> {
            assertThat(f.price()).isNotNull();
            assertThat(f.amountYi()).isNotNull();
        });
        // 「这个数是哪来的」必须写出来，否则用户无从判断可信度
        assertThat(result.summary().notes())
                .anyMatch(n -> n.contains("指数行情来源") && n.contains("腾讯（兜底）"));
    }

    @Test
    void sinaIsOnlyAskedForWhatTencentAlsoMissed() {
        // 一层一层往下补：腾讯只补到 1 只，新浪就只该被问剩下的 6 只，
        // 而不是把 7 只再问一遍——兜底链的第二价值就是别把外呼量乘三
        Map<String, MarketDataClient.EtfQuote> fromTencent = new java.util.LinkedHashMap<>();
        List<String> codes = poolEtfCodes(indexPool);
        fromTencent.put(codes.get(0), quote(codes.get(0), AltQuoteSource.PROVIDER_TENCENT));
        when(alt.fetchTencentQuotes(any())).thenReturn(fromTencent);
        when(alt.fetchSinaQuotes(any())).thenReturn(allFrom(AltQuoteSource.PROVIDER_SINA));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        org.mockito.ArgumentCaptor<List<String>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(alt).fetchSinaQuotes(captor.capture());
        assertThat(captor.getValue()).containsExactlyElementsOf(codes.subList(1, codes.size()));
        assertThat(result.summary().notes()).anyMatch(n -> n.contains("新浪（兜底）"));
    }

    @Test
    void aSinaQuoteSaysWhichFieldItCannotProvide() {
        // 新浪不给规模。规模那一格于是是空的——原因必须写在卡上，
        // 否则读者会以为是我们没取到，或者以为那是 0
        when(alt.fetchSinaQuotes(any())).thenReturn(allFrom(AltQuoteSource.PROVIDER_SINA));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        assertThat(result.indexFunds()).allSatisfy(f -> {
            assertThat(f.scaleYi()).isNull();
            // 缺值的原因必须落在**那一格的状态串**上。放在 notes 里等于把原因挪到了
            // 卡片另一处，而格子本身还是空的——那正是用户截图里抱怨的样子
            assertThat(f.scaleStatus())
                    .contains("新浪兜底源").contains("不提供基金规模字段");
            // 同一个源给得到的字段不能跟着一起空
            assertThat(f.price()).isNotNull();
            assertThat(f.priceStatus()).isNull();
            assertThat(f.amountYi()).isNotNull();
        });
    }

    @Test
    void aThrottledFallbackSourceDoesNotLockOutEastmoney() {
        // 这是这个改动要修的真问题：腾讯挨的限流不该把东财的清单请求一起锁死，
        // 否则页面上那句「东财在限流」就是假的。
        // TTL 0 → 第二次扫描必须重新拉清单，所以 fetchUniverse 的调用次数就能证明它没被挡住。
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(alt.fetchTencentQuotes(any())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("腾讯限流", null));
        ScreenerCache cache = new ScreenerCache(0, 20, 60, 10);
        StockScreenerService svc = service(3, cache);

        svc.scan(ScreenerParams.defaults());
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();

        svc.scan(ScreenerParams.defaults());

        verify(client, times(2)).fetchUniverse();
        assertThat(cache.inCooldown()).isFalse();
    }

    @Test
    void aThrottledFallbackSourceIsNotAskedAgainOnTheNextScan() {
        // 反过来也要成立：冷却期内不该再去打腾讯——那只是把封禁推得更深
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(alt.fetchTencentQuotes(any())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("腾讯限流", null));
        ScreenerCache cache = new ScreenerCache(0, 20, 60, 10);
        StockScreenerService svc = service(3, cache);

        svc.scan(ScreenerParams.defaults());
        svc.scan(ScreenerParams.defaults());

        verify(alt, times(1)).fetchTencentQuotes(any());
    }

    @Test
    void theFallbackBudgetIsCappedAndSaysHowManyItLeftOut() {
        // 池子扩到 200 条时，「每只一条日线」正是把 IP 封得更深的做法。
        // 额度用尽必须说出来，不能悄悄少几只——那和「这个指数本来就没有数据」看起来一模一样。
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(client.fetchKline(anyString()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("日线不可达，价格位置未确认"));
        when(alt.fetchTencentKline(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(bars(), AltQuoteSource.PROVIDER_TENCENT));
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);
        int poolSize = indexPool.withEtf().size();

        ScreenerResultDTO result = service(3, cache, 2).scan(ScreenerParams.defaults());

        verify(alt, times(2)).fetchTencentKline(anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(result.summary().degradations()).anyMatch(
                d -> d.contains("兜底额度") && d.contains((poolSize - 2) + " 只"));
    }

    @Test
    void theKlineFallbackKeepsThePricePositionThatEastmoneyFailedToGive() {
        // 用户看到的「一年价格分位 / 距一年最高点」全是破折号，根因就在这里：
        // 东财一条日线都没回，而 Java 侧当时没有任何兜底
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(client.fetchKline(anyString()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("日线不可达，价格位置未确认"));
        when(alt.fetchTencentKline(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(bars(), AltQuoteSource.PROVIDER_TENCENT));
        ScreenerParams p = new ScreenerParams();
        p.setMode("index_only");

        ScreenerResultDTO result = service(3).scan(p);

        assertThat(result.indexFunds()).isNotEmpty();
        assertThat(result.indexFunds()).allSatisfy(f -> {
            assertThat(f.pricePercentile()).isNotNull();
            assertThat(f.annualizedVolatility()).isNotNull();
        });
        assertThat(result.summary().notes()).anyMatch(n -> n.contains("腾讯兜底源"));
    }

    @Test
    void aFailedFallbackDoesNotEraseTheFactThatEastmoneyThrottledUs() {
        // 兜底也失败时，主源那句「被限流」是更准确的解释。若把它换成「腾讯日线为空」，
        // 东财的限流信号就消失在结果里了——而它是决定要不要进冷却的那一条。
        when(client.fetchUniverse()).thenReturn(snapshot(qualifiedUniverse(30)));
        when(client.fetchKline(anyString())).thenReturn(
                MarketDataClient.KlineOutcome.throttled("行情源限流，价格位置未能更新"));
        when(alt.fetchTencentKline(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("腾讯日线为空",
                        AltQuoteSource.PROVIDER_TENCENT));
        ScreenerCache cache = new ScreenerCache(15, 20, 60, 10);

        ScreenerResultDTO result = service(3, cache).scan(ScreenerParams.defaults());

        assertThat(cache.inCooldown()).isTrue();   // 东财那一路
        assertThat(result.summary().degradations()).anyMatch(d -> d.contains("东财"));
        // 同一只不会再被算进「未取到」里：两个计数是对最终结果的一次划分，不能重叠
        assertThat(result.summary().degradations()).noneMatch(d -> d.contains("日线未取到"));
    }
}