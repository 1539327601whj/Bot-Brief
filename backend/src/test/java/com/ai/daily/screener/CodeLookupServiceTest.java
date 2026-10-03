package com.ai.daily.screener;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CodeLookupService} 的测试。这里正是「代码查询」真正容易出错的地方：
 * **判类型、按来源分档、缺数据怎么说**。
 *
 * <p>全部外呼都被 mock 掉，日期被钉在 {@link #TODAY}——后者不是洁癖：一旦用例里出现
 * {@code LocalDate.now()}，「十年基线」这类断言就会随运行日期漂移，于是同一条用例
 * 工作日绿、周末红（{@code ScreenerPrefetchTask} 上踩过这个坑）。
 *
 * <p>日线序列按**交易日**生成（越过周末），不是按自然日：{@code lookup-kline-limit}
 * 是 2600 **根**日线，约合 10.7 年。按自然日造 2600 天只有 7.1 年，
 * 那样测出来的「十年档为空」是测试自己造的假象，不是生产行为。
 */
class CodeLookupServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    /** 十年零七个月的交易日，足够覆盖到「十年前当日」。 */
    private static final int DECADE_BARS = 2600;

    private MarketDataClient marketDataClient;
    private AltQuoteSource altQuoteSource;
    private StockValuationClient stockValuationClient;
    private OffPoolIndexClient offPoolIndexClient;
    private IndexFundPool indexPool;
    private MarketValuationHistoryService valuationService;
    private ScreenerCache cache;

    @BeforeEach
    void setUp() {
        marketDataClient = mock(MarketDataClient.class);
        altQuoteSource = mock(AltQuoteSource.class);
        stockValuationClient = mock(StockValuationClient.class);
        offPoolIndexClient = mock(OffPoolIndexClient.class);
        indexPool = mock(IndexFundPool.class);
        valuationService = mock(MarketValuationHistoryService.class);
        cache = new ScreenerCache(15, 20, 60, 10);
    }

    private CodeLookupService service() {
        return serviceWithClock(() -> LocalDateTime.of(TODAY, LocalDateTime.now().toLocalTime()));
    }

    private interface Clock {
        LocalDateTime now();
    }

    private CodeLookupService serviceWithClock(Clock clock) {
        return new CodeLookupService(marketDataClient, altQuoteSource, stockValuationClient,
                offPoolIndexClient, indexPool, valuationService, cache, DECADE_BARS) {
            @Override
            LocalDate localDateNow() {
                return clock.now().toLocalDate();
            }

            @Override
            LocalDateTime localDateTimeNow() {
                return clock.now();
            }
        };
    }

    // ==================================================================
    // 造数据
    // ==================================================================

    private static IndexFundPool.Fund csindexFund(String indexCode, String csindexCode,
                                                  String etfCode, int market, String name) {
        return new IndexFundPool.Fund(indexCode, name, "broad", IndexFundPool.SOURCE_CSINDEX,
                IndexFundPool.METHOD_CSINDEX_ROLLING_10Y, csindexCode, null, etfCode, market,
                name + "ETF", name);
    }

    private static IndexFundPool.Fund danjuanFund(String indexCode, String danjuanCode,
                                                  String etfCode, int market, String name) {
        return new IndexFundPool.Fund(indexCode, name, "overseas", IndexFundPool.SOURCE_DANJUAN,
                IndexFundPool.METHOD_DANJUAN, null, danjuanCode, etfCode, market, name + "ETF", name);
    }

    private static StockRow row(String code, String name, String price, String pct, String pe) {
        StockRow r = new StockRow();
        r.setCode(code);
        r.setName(name);
        r.setMarket(code.startsWith("6") || code.startsWith("5") || code.startsWith("9") ? 1 : 0);
        r.setPrice(price == null ? null : new BigDecimal(price));
        r.setPctChange(pct == null ? null : new BigDecimal(pct));
        r.setPeTtm(pe == null ? null : new BigDecimal(pe));
        r.setPb(new BigDecimal("1.50"));
        r.setIndustry("电气机械");
        r.setTotalMarketCap(new BigDecimal("120000000000"));
        return r;
    }

    private void givenQuotes(StockRow... rows) {
        Map<String, StockRow> map = new LinkedHashMap<>();
        for (StockRow r : rows) map.put(r.getCode(), r);
        when(marketDataClient.fetchQuotes(anyList())).thenReturn(map);
    }

    /**
     * 过去 {@code count} 个交易日，升序，收盘价单调递增——每一档的基线都存在且互不相同。
     *
     * <p><b>必须跳过公历假日，不能只跳周末</b>：A 股一年约 243 个交易日，只跳周末的话
     * 一年有 261 个「交易日」。这个差别会直接决定「十年」那一档测出来是有数还是空——
     * {@code lookup-kline-limit} 是 2600 根，按 243 天/年 ≈ 10.7 年（够到十年前），
     * 按 261 天/年只有约 9.97 年（刚好差一点）。测出来的「历史不足」就会是测试自己造的假象。
     *
     * <p>这里只列公历固定的假日（元旦、五一、国庆）。春节/清明/端午按农历浮动，不逐条列——
     * 差几天不影响结论，{@link LookbackCalculator#STALE_DAYS} 那 15 天的宽度足够吸收。
     */
    private static List<PricePositionCalculator.Bar> tradingBars(int count, LocalDate last) {
        List<LocalDate> days = new ArrayList<>(count);
        LocalDate d = last;
        while (days.size() < count) {
            if (isTradingDay(d)) {
                days.add(d);
            }
            d = d.minusDays(1);
        }
        Collections.reverse(days);
        List<PricePositionCalculator.Bar> bars = new ArrayList<>(count);
        for (int i = 0; i < days.size(); i++) {
            bars.add(new PricePositionCalculator.Bar(days.get(i), BigDecimal.valueOf(100 + i)));
        }
        return bars;
    }

    private static boolean isTradingDay(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        int month = d.getMonthValue();
        int day = d.getDayOfMonth();
        if (month == 1 && day == 1) return false;
        if (month == 5 && day <= 3) return false;
        return !(month == 10 && day <= 7);
    }

    private void givenKline(List<PricePositionCalculator.Bar> bars) {
        when(marketDataClient.fetchKline(anyString(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(bars));
    }

    /** 过去 {@code weeks} 周的 PE，从 10 缓升到 20 —— 末点是窗口内最高 → 当日分位 100。 */
    private static List<RollingPercentile.Point> densePePoints(int weeks) {
        List<RollingPercentile.Point> points = new ArrayList<>(weeks);
        for (int i = 0; i < weeks; i++) {
            LocalDate day = TODAY.minusWeeks(weeks - 1L - i);
            BigDecimal pe = BigDecimal.valueOf(10.0 + 10.0 * i / (weeks - 1.0))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            points.add(new RollingPercentile.Point(day, pe));
        }
        return points;
    }

    private static List<StockValuationClient.Point> denseStockPoints(int weeks) {
        List<StockValuationClient.Point> points = new ArrayList<>(weeks);
        for (RollingPercentile.Point p : densePePoints(weeks)) {
            points.add(new StockValuationClient.Point(p.date(), p.value(),
                    new BigDecimal("3.41"), new BigDecimal("82.49")));
        }
        return points;
    }

    private static OffPoolIndexClient.Result csindexOk(List<RollingPercentile.Point> points) {
        return OffPoolIndexClient.Result.ok(IndexFundPool.SOURCE_CSINDEX,
                OffPoolIndexResolver.LABEL_CSINDEX, IndexFundPool.METHOD_CSINDEX_ROLLING_10Y,
                null, points.get(points.size() - 1).value(), new BigDecimal("100.0000"),
                points.get(points.size() - 1).date(), points);
    }

    private static MarketValuationHistory dbRow(LocalDate day, String pe, String percentile) {
        MarketValuationHistory h = new MarketValuationHistory();
        h.setIndexCode("SH000300");
        h.setPeTtm(new BigDecimal(pe));
        h.setPePercentile(new BigDecimal(percentile));
        h.setPercentileMethod(IndexFundPool.METHOD_CSINDEX_ROLLING_10Y);
        h.setTradeDate(day);
        return h;
    }

    private static CodeLookupDTO.LookbackCell cell(CodeLookupDTO dto, String label) {
        return cell(dto.valuation().lookbacks(), label);
    }

    private static CodeLookupDTO.LookbackCell priceCell(CodeLookupDTO dto, String label) {
        return cell(dto.priceLookbacks(), label);
    }

    private static CodeLookupDTO.LookbackCell cell(List<CodeLookupDTO.LookbackCell> cells, String label) {
        return cells.stream().filter(c -> label.equals(c.label())).findFirst().orElseThrow();
    }

    // ==================================================================
    // 判类型：靠码段表，不靠「哪个源先回」
    // ==================================================================

    @Test
    void theDigitSegmentsDecideStockVersusFundAndUnknownSegmentsDecideNothing() {
        assertThat(CodeLookupService.marketOf("600519").kind()).isEqualTo(CodeLookupDTO.Kind.STOCK);
        assertThat(CodeLookupService.marketOf("688981").secidPrefix()).isEqualTo(1);
        assertThat(CodeLookupService.marketOf("300274").secidPrefix()).isEqualTo(0);
        assertThat(CodeLookupService.marketOf("000001").kind()).isEqualTo(CodeLookupDTO.Kind.STOCK);
        assertThat(CodeLookupService.marketOf("430047").kind()).isEqualTo(CodeLookupDTO.Kind.STOCK);
        assertThat(CodeLookupService.marketOf("510300").kind()).isEqualTo(CodeLookupDTO.Kind.FUND);
        assertThat(CodeLookupService.marketOf("159915").kind()).isEqualTo(CodeLookupDTO.Kind.FUND);
        assertThat(CodeLookupService.marketOf("900901").secidPrefix()).isEqualTo(1);

        // 930740 / 931xxx / 399006 是中证与深证的**指数**码段，不是 A 股证券。
        // 猜一个前缀的代价很大：0.000300 在深市是一只与沪深300 毫无关系的票，
        // 而页面上只会显示一个看着合理的名字。
        assertThat(CodeLookupService.marketOf("930740")).isNull();
        assertThat(CodeLookupService.marketOf("931775")).isNull();
        assertThat(CodeLookupService.marketOf("399006")).isNull();
        assertThat(CodeLookupService.marketOf("NDX")).isNull();
    }

    @Test
    void illegalCodeShapesAreRejectedBeforeAnythingIsDialled() {
        for (String bad : new String[]{"", "   ", "0000-300", "abc def", "一二三四五六", "1234567890123"}) {
            assertThatThrownBy(() -> service().lookup(bad))
                    .as("code=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service().lookup(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(marketDataClient, offPoolIndexClient, stockValuationClient);
    }

    // ==================================================================
    // 池内指数
    // ==================================================================

    @Test
    void aPoolEtfIsReadAsItsIndexAndGetsAllEightPriceAndPercentileBands() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.INDEX_FUND);
        assertThat(dto.resolvedCode()).isEqualTo("SH000300");
        assertThat(dto.name()).isEqualTo("沪深300ETF");
        assertThat(dto.quote().provider()).isEqualTo(MarketDataClient.PROVIDER_EASTMONEY);

        assertThat(dto.priceLookbacks()).extracting(CodeLookupDTO.LookbackCell::label)
                .containsExactly("昨", "周", "月", "半年", "一年", "三年", "五年", "十年");
        assertThat(dto.priceLookbacks()).allSatisfy(c -> assertThat(c.present()).isTrue());
        assertThat(dto.valuation().lookbacks()).allSatisfy(c -> assertThat(c.present()).isTrue());
        assertThat(dto.valuation().pePercentile()).isEqualByComparingTo("100.0000");
        assertThat(dto.valuation().tradeDate()).isEqualTo("2026-09-30");

        // 十年档的基线必须真的落在十年前那一天上，而不是「序列里最老的那一点」。
        // 价格那一行是**日频**，所以正好取到当日。
        assertThat(priceCell(dto, "十年").baselineDate()).isEqualTo("2016-09-30");
        assertThat(priceCell(dto, "五年").baselineDate()).isEqualTo("2021-09-30");
        // 分位那一行是**周频观测**（中证官网就是日频，这里的 fixture 用周频省内存），
        // 只能取到「当日或之前最近的一次观测」——所以说的是它在目标日附近，
        // 而不是恰好等于。把这一条写成相等就变成在测 fixture 的密度，不是测算法。
        assertThat(cell(dto, "十年").baselineDate()).startsWith("2016-09");
        assertThat(cell(dto, "五年").baselineDate()).startsWith("2021-09");

        // 长档日线用的是 lookup-kline-limit，不是筛选器那 250 根
        verify(marketDataClient).fetchKline(eq("1.510300"), eq(DECADE_BARS));
    }

    @Test
    void aBareIndexCodeAndAPrefixedLetterCodeBothLandOnTheirPoolIndex() {
        IndexFundPool.Fund fund = csindexFund("SH000300", "000300", "510300", 1, "沪深300");
        when(indexPool.byIndexCode("SH000300")).thenReturn(fund);
        IndexFundPool.Fund h = csindexFund("CSIH30533", "H30533", "513050", 1, "中概互联50");
        when(indexPool.byCsindexCode("H30533")).thenReturn(h);

        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null),
                row("513050", "中概互联ETF", "1.20", "0.10", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        // 裸代码 000300 → 试 "SH"+code 命中
        assertThat(service().lookup("000300").resolvedCode()).isEqualTo("SH000300");
        // H30533 → 中证官网的入参代码命中
        assertThat(service().lookup("H30533").resolvedCode()).isEqualTo("CSIH30533");
    }

    @Test
    void aDanjuanPoolIndexLeavesTheLongBandsEmptyAndSaysTheSourceHasNoHistory() {
        when(indexPool.byIndexCode("NDX"))
                .thenReturn(danjuanFund("NDX", "NDX", "513100", 1, "纳指100"));
        givenQuotes(row("513100", "纳指100ETF", "1.80", "-0.30", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        // 项目库里只有最近几行——蛋卷只给当日值，这些行是一天一天攒出来的
        when(valuationService.historyBetween(eq("NDX"), eq(IndexFundPool.METHOD_DANJUAN), any(), any()))
                .thenReturn(List.of(
                        dbRow(TODAY.minusDays(2), "35.0", "87.00"),
                        dbRow(TODAY.minusDays(1), "35.1", "87.20"),
                        dbRow(TODAY, "35.2", "87.32")));

        CodeLookupDTO dto = service().lookup("NDX");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.INDEX);
        assertThat(dto.valuation().pePercentile()).isEqualByComparingTo("87.32");
        assertThat(dto.valuation().percentileMethod()).isEqualTo(IndexFundPool.METHOD_DANJUAN);

        // 「昨」有着落，长档没有——而**没有的原因必须写出来**，不能留一个空格。
        assertThat(cell(dto, "昨").present()).isTrue();
        for (String label : new String[]{"三年", "五年", "十年"}) {
            assertThat(cell(dto, label).present()).as("档位 %s", label).isFalse();
            assertThat(cell(dto, label).status())
                    .as("档位 %s 的原因", label)
                    .isEqualTo(CodeLookupService.DANJUAN_NO_HISTORY);
        }
        // 价格八档照常（日线来自代表 ETF），蛋卷不提供历史不影响它
        assertThat(dto.priceLookbacks()).allSatisfy(c -> assertThat(c.present()).isTrue());
    }

    @Test
    void whenCsindexIsDownThePoolIndexFallsBackToTheProjectLibraryAndSaysSo() {
        when(indexPool.byEtfCode("510500"))
                .thenReturn(csindexFund("SH000905", "000905", "510500", 1, "中证500"));
        givenQuotes(row("510500", "中证500ETF", "6.10", "0.10", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any()))
                .thenReturn(OffPoolIndexClient.Result.failed(IndexFundPool.SOURCE_CSINDEX,
                        OffPoolIndexResolver.LABEL_CSINDEX, "中证官网不可达（连接超时）"));
        when(valuationService.historyBetween(eq("SH000905"),
                eq(IndexFundPool.METHOD_CSINDEX_ROLLING_10Y), any(), any())).thenReturn(List.of(
                        dbRow(TODAY.minusYears(3), "25.0", "30.00"),
                        dbRow(TODAY, "28.0", "55.00")));

        CodeLookupService svc = service();
        CodeLookupDTO dto = svc.lookup("510500");

        // 退路用的是**同一个算法写进库的行**——换的是数据来源，不是口径。
        // 但换了什么必须写在结果里，否则「今天这个数是从哪来的」就说不清了。
        assertThat(dto.valuation().pePercentile()).isEqualByComparingTo("55.00");
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("项目库"));
        assertThat(cell(dto, "三年").present()).isTrue();

        // 降级过的结果**不进缓存**：否则用户点「再试一次」拿到的还是那句话，
        // 看起来像功能坏了，而源其实早就恢复了。
        assertThat(svc.cacheSize()).isZero();
    }

    // ==================================================================
    // 池外指数
    // ==================================================================

    @Test
    void aSixDigitCodeOutsideEveryStockSegmentGoesToCsindexAndGetsFullHistory() {
        // 930740 是中证指数的码段，不在任何 A 股码段里——直接走中证官网。
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));
        givenKline(tradingBars(DECADE_BARS, TODAY));

        CodeLookupDTO dto = service().lookup("930740");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.OFF_POOL_INDEX);
        assertThat(dto.resolvedCode()).isEqualTo("930740");
        assertThat(dto.valuation().available()).isTrue();
        assertThat(dto.valuation().percentileMethod())
                .isEqualTo(IndexFundPool.METHOD_CSINDEX_ROLLING_10Y);
        assertThat(dto.valuation().lookbacks()).allSatisfy(c -> assertThat(c.present()).isTrue());
        // 池外指数不取现价：指数代码在报价源里没有可靠的形态。这句话要说出来，
        // 而不是留下一格空白让人以为源挂了。
        assertThat(dto.quote()).isNull();
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("不取现价"));
        // 没有 quote 不等于价格行没有「今」：回看的八档基线来自日线，今天也取自同一根日线，
        // 否则那一行会以「昨」开头，读不通。
        assertThat(dto.currentPrice()).isEqualByComparingTo("2699");
        assertThat(dto.currentPriceDate()).isEqualTo("2026-09-30");
    }

    @Test
    void aDanjuanOffPoolIndexReportsItsCurrentValueAndSaysHistoryIsUnavailable() {
        when(offPoolIndexClient.fetch(any())).thenReturn(OffPoolIndexClient.Result.ok(
                IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                IndexFundPool.METHOD_DANJUAN, "恒生指数", new BigDecimal("11.1"),
                new BigDecimal("42.00"), TODAY, List.of()));

        CodeLookupDTO dto = service().lookup("HSI");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.OFF_POOL_INDEX);
        assertThat(dto.valuation().available()).isTrue();
        assertThat(dto.valuation().peTtm()).isEqualByComparingTo("11.1");
        assertThat(dto.valuation().pePercentile()).isEqualByComparingTo("42.00");
        assertThat(dto.valuation().historyLength()).isNull();
        for (CodeLookupDTO.LookbackCell c : dto.valuation().lookbacks()) {
            assertThat(c.present()).as("档位 %s 不该有值", c.label()).isFalse();
            // 八档**都要说「源不提供」**，不是那个通用的「未取到任何有效观测」——
            // 前者只能换源，后者重试就行，指向的下一步完全不同。
            assertThat(c.status()).as("档位 %s 的原因", c.label())
                    .isEqualTo(CodeLookupService.DANJUAN_NO_HISTORY);
        }
        // 池外蛋卷指数连行情代码都没有（东财不按指数名报价），价格八档也得说明原因，
        // 而不是留八格空白让人以为功能坏了。
        assertThat(dto.priceLookbacks()).allSatisfy(c -> {
            assertThat(c.present()).isFalse();
            assertThat(c.status()).contains("蛋卷");
        });
    }

    @Test
    void aCodeNoSourceKnowsBecomes404RatherThanAFailedLookup() {
        // 源明确说了「我这里没有这个代码」→ 404。**这不是 503**：
        // 503 会让用户一直重试一个根本不存在的代码。
        when(offPoolIndexClient.fetch(any())).thenReturn(OffPoolIndexClient.Result.notFound(
                IndexFundPool.SOURCE_CSINDEX, OffPoolIndexResolver.LABEL_CSINDEX,
                "中证官网没有 999999 的 PE 历史"));

        assertThatThrownBy(() -> service().lookup("999999"))
                .isInstanceOf(CodeLookupService.CodeNotFoundException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void aSourceThatIsDownIs503Not404() {
        when(offPoolIndexClient.fetch(any())).thenReturn(OffPoolIndexClient.Result.failed(
                IndexFundPool.SOURCE_CSINDEX, OffPoolIndexResolver.LABEL_CSINDEX,
                "中证官网不可达（连接超时）"));
        givenKline(tradingBars(400, TODAY));

        CodeLookupDTO dto = service().lookup("930740");

        // 源不可达时**不能**回 404——那等于假装「这个代码不存在」。
        // 页面照常出（价格那半还有），估值那半写明取不到。
        assertThat(dto.valuation().available()).isFalse();
        assertThat(dto.valuation().notes()).anySatisfy(n -> assertThat(n).contains("不可达"));
    }

    // ==================================================================
    // 个股
    // ==================================================================

    @Test
    void aStockGetsItsPercentileFromTheValuationAnalysisHistory() {
        givenQuotes(row("300274", "阳光电源", "82.49", "2.98", "15.57"));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.ok("300274", "阳光电源", denseStockPoints(580), 580));

        CodeLookupDTO dto = service().lookup("300274");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.STOCK);
        assertThat(dto.valuation().source()).isEqualTo(CodeLookupService.SOURCE_VALUEANALYSIS);
        assertThat(dto.valuation().percentileMethod())
                .isEqualTo(StockValuationClient.METHOD_STOCK_VALUATION_ROLLING_10Y);
        assertThat(dto.valuation().peTtm()).isEqualByComparingTo("20.0000");
        assertThat(dto.valuation().historyLength()).isEqualTo(580);
        assertThat(dto.valuation().lookbacks()).extracting(CodeLookupDTO.LookbackCell::label)
                .containsExactly("昨", "周", "月", "半年", "一年", "三年", "五年", "十年");
        assertThat(dto.valuation().lookbacks()).allSatisfy(c -> assertThat(c.present()).isTrue());
        assertThat(dto.position().available()).isTrue();
        assertThat(dto.position().pricePercentile()).isNotNull();

        // push2 的 f115 与估值分析的 PE 是**两份快照**，都端出来、各标各的来源。
        assertThat(dto.quote().peTtm()).isEqualByComparingTo("15.57");
    }

    @Test
    void aStockHistoryShorterThanTenYearsSaysSoInsteadOfBorrowingANumber() {
        // 实测这个源只回约 8.7 年（2100 行）。十年那一档必须说「历史不足」——
        // 而不是拿窗口里最老的一天顶上，更不是那句已经作废的「个股没有 PE 分位」。
        givenQuotes(row("300274", "阳光电源", "82.49", "2.98", "15.57"));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.ok("300274", "阳光电源", denseStockPoints(450), 2100));

        CodeLookupDTO dto = service().lookup("300274");

        assertThat(cell(dto, "五年").present()).isTrue();
        assertThat(cell(dto, "十年").present()).isFalse();
        assertThat(cell(dto, "十年").status())
                .contains("不足以确认十年基线")
                .contains("8.6");   // 450 周 ≈ 8.6 年
    }

    @Test
    void aStockWhoseValuationSourceIsDownStillShowsItsPriceAndSaysWhyThePercentileIsMissing() {
        givenQuotes(row("300274", "阳光电源", "82.49", "2.98", "15.57"));
        givenKline(tradingBars(400, TODAY));
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.failed("300274", "估值分析源不可达（连接超时）"));

        CodeLookupDTO dto = service().lookup("300274");

        assertThat(dto.quote().price()).isEqualByComparingTo("82.49");
        assertThat(dto.valuation().available()).isFalse();
        assertThat(dto.valuation().notes()).anySatisfy(n -> assertThat(n).contains("不可达"));
        // 价格那半照常：一个源挂了不该把整页变成错误页
        assertThat(dto.priceLookbacks()).anySatisfy(c -> assertThat(c.present()).isTrue());
    }

    @Test
    void daysWithoutPeAreCountedAndSaidOutLoudRatherThanBecomingZero() {
        // 亏损或未披露的日子没有 PE。把它当成 0 会让那天变成「史上最便宜」，
        // 而整条分位曲线只会偏一点——页面上永远看不出来。
        givenQuotes(row("300274", "阳光电源", "82.49", "2.98", "15.57"));
        givenKline(tradingBars(400, TODAY));
        // 300 个有 PE 的观测，外加 1 行没有 PE（当天没披露）——那一行照常留在 points 里，
        // 但要从 PE 窗口里剔除，于是分母少一天，分位会偏一点。
        List<StockValuationClient.Point> points = new ArrayList<>(denseStockPoints(300));
        points.add(new StockValuationClient.Point(TODAY.minusDays(200), null,
                new BigDecimal("3.41"), new BigDecimal("82.49")));
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.ok("300274", "阳光电源", points, 301));

        CodeLookupDTO dto = service().lookup("300274");

        assertThat(dto.valuation().historyLength()).isEqualTo(300);
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("1 天没有 PE"));
    }

    // ==================================================================
    // 兜底行情
    // ==================================================================

    @Test
    void whenEastmoneyIsUnreachableTheQuoteComesFromTencentAndTheSourceIsWrittenOut() {
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new MarketDataException("行情批量接口全部失败—— 第 1 批：连接超时"));
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenReturn(Map.of("300274",
                new MarketDataClient.EtfQuote("300274", "阳光电源", new BigDecimal("82.49"),
                        new BigDecimal("2.98"), null, new BigDecimal("1200"),
                        new BigDecimal("15.60"), new BigDecimal("3.41"),
                        AltQuoteSource.PROVIDER_TENCENT)));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.failed("300274", "估值分析源不可达"));

        CodeLookupDTO dto = service().lookup("300274");

        assertThat(dto.quote().provider()).isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
        // 用了兜底源这件事必须写出来：同一天两个源的价可能不一样，而页面只显示一个数。
        // 来源既在 providerLabel 里（给每一格标出处），也在 notes 里（说明为什么换了源）。
        assertThat(dto.quote().providerLabel()).contains("腾讯").contains("兜底");
        assertThat(dto.quote().price()).isEqualByComparingTo("82.49");
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("兜底源"));
        // 注意没有走「按池外指数重试」那条路——那是因为**源答了**，不是因为源挂了。
        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.STOCK);
    }

    @Test
    void aStockThatNoSourceHasIsReReadAsAnOffPoolIndexInsteadOfBeingDeclaredMissing() {
        // 000985 既可能是深市股票代码，也是中证全指的代码。行情源**明确回了**
        // 「没有这只标的」，这时才按池外指数再试一次——前提是源答了。
        // 源挂了不能走这条路，否则东财一挂，所有 6 位代码都会被读成指数。
        givenQuotes();   // 请求成功、结果里没有这一行 = 明确回答「没有」
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));
        givenKline(tradingBars(DECADE_BARS, TODAY));

        CodeLookupDTO dto = service().lookup("000985");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.OFF_POOL_INDEX);
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("按池外指数"));
    }

    @Test
    void whenNoQuoteSourceAnswersAtAllTheLookupFailsInsteadOfReturningAStub() {
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new MarketDataException("行情批量接口全部失败"));
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenThrow(new RuntimeException("超时"));
        when(altQuoteSource.fetchSinaQuotes(anyList())).thenThrow(new RuntimeException("超时"));

        // 谁都没答 = 我们并不知道这只票存不存在 → 503，不是 404，也不是一个空壳 DTO
        assertThatThrownBy(() -> service().lookup("300274"))
                .isInstanceOf(MarketDataException.class)
                .isNotInstanceOf(CodeLookupService.CodeNotFoundException.class);
    }

    // ==================================================================
    // 限流
    // ==================================================================

    @Test
    void whileInCooldownNotASingleRequestIsSent() {
        // 冷却是**纪律**不是性能优化：被限流时每一次重试都在把这个 IP 往更深的封禁里推。
        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, 10);

        CodeLookupService svc = service();
        assertThatThrownBy(() -> svc.lookup("510300"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .hasMessageContaining("冷却");

        verifyNoInteractions(marketDataClient, offPoolIndexClient, stockValuationClient, altQuoteSource);
    }

    @Test
    void aThrottledQuoteIsRaisedAndLocksTheSourceInsteadOfBeingRetried() {
        when(marketDataClient.fetchQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("被限流了", null));

        CodeLookupService svc = service();
        assertThatThrownBy(() -> svc.lookup("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);

        // 只把 429 返给前端而不锁住自己，用户点一次「再试一次」就又是一次真实外呼
        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isTrue();
        verify(marketDataClient, never()).fetchKline(anyString(), anyInt());
    }

    @Test
    void aThrottledKlineEntersCooldownSoTheNextClickIsBlocked() {
        givenQuotes(row("300274", "阳光电源", "82.49", "2.98", "15.57"));
        // 估值先问（不存在的代码要在那一步现形），所以个股这条路也要给它一个回答
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.failed("300274", "估值分析源不可达"));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.throttled("行情源限流，价格位置未能更新"));

        CodeLookupService svc = service();
        assertThatThrownBy(() -> svc.lookup("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isTrue();
    }

    // ==================================================================
    // 缓存
    // ==================================================================

    @Test
    void aSecondClickWithinTheTtlIsServedFromTheCacheAndDoesNotDialAgain() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupService svc = service();
        CodeLookupDTO first = svc.lookup("510300");
        CodeLookupDTO second = svc.lookup("510300");

        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        // 命中缓存的那一次**不能**再外呼：连点不该被放大成对东财的压测
        verify(marketDataClient, times(1)).fetchKline(anyString(), anyInt());
        verify(offPoolIndexClient, times(1)).fetch(any());
        // 缓存的时刻要写出来，不能假装「是现在取的」
        assertThat(second.snapshotAt()).isEqualTo(first.snapshotAt());
    }

    @Test
    void theCacheExpiresSoAStaleNumberIsNotServedForever() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        LocalDateTime[] now = {TODAY.atTime(10, 0)};
        CodeLookupService svc = serviceWithClock(() -> now[0]);

        assertThat(svc.lookup("510300").fromCache()).isFalse();
        assertThat(svc.lookup("510300").fromCache()).isTrue();

        now[0] = now[0].plusSeconds(CodeLookupService.CACHE_TTL_SECONDS + 1);
        assertThat(svc.lookup("510300").fromCache()).isFalse();
        verify(marketDataClient, times(2)).fetchKline(anyString(), anyInt());
    }
}