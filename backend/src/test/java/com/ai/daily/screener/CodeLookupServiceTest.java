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
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeastOnce;
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

    /** 「现在几点」也钉死。时刻跟着真表走，断言就变成了在赌时钟的精度（见 {@link #service()}）。 */
    private static final LocalDateTime FIXED_NOW = TODAY.atTime(10, 0);

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

        // 兜底源**默认显式地回「问过了，没有这只标的」**（空 Map），而不是靠 Mockito 的
        // 默认返回值。两者数值一样，但后者是「没 stub」的副作用——用例作者看不出
        // 自己到底是在测「源答了没有」还是「压根没问」，而这两件事在本类里是**不同的结果**。
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenReturn(Map.of());
        when(altQuoteSource.fetchSinaQuotes(anyList())).thenReturn(Map.of());
    }

    /** 兜底日线的根数。<b>与 {@code lookupKlineLimit} 不是一回事</b>，见生产代码的构建器注释。 */
    private static final int FALLBACK_KLINE_LIMIT = 800;

    private CodeLookupService service() {
        // **日期和时刻都钉死**。原来是 `LocalDateTime.of(TODAY, LocalDateTime.now().toLocalTime())`
        // ——只钉日期、时刻取真表，于是每次调用 `localDateTimeNow()` 都返回一个**不同的纳秒值**。
        // 这在 Windows（毫秒精度）上看不出来，两次调用落在同一毫秒里，值就相等了；
        // 到 Linux 的无纳秒退让版（CI）就成了「同一条用例本机绿、CI 红」。
        // 一个用例不该依赖「两次取现在时刻恰好落进同一格」这种事。
        return serviceWithClock(() -> FIXED_NOW);
    }

    private interface Clock {
        LocalDateTime now();
    }

    /**
     * **每读一次就前进一秒**的时钟：把「一次查询到底读了几次钟」变成字符串上看得见的东西。
     *
     * <p>固定时钟照不出「一次查询读了两次钟」这个回归——两次读返回同一个值，坏实现也是绿的。
     * 秒而不是纳秒，是因为 {@code stamp()} 会把时刻截到秒：纳秒步长会被截成同一秒，
     * 于是坏实现照样绿，成了一条假阴性用例。
     *
     * <p>只给需要它的用例 opt-in，**不要**换掉 {@link #service()} 的默认时钟：
     * 读钟次数一变，其它用例的时间也跟着漂。
     */
    private static final class SteppingClock implements Clock {
        private LocalDateTime t;

        SteppingClock(LocalDateTime start) {
            this.t = start;
        }

        @Override
        public LocalDateTime now() {
            LocalDateTime v = t;
            t = t.plusSeconds(1);
            return v;
        }

        /** 拨表：两次点击之间推进时间，用来跨过 TTL 或制造「不是现在取的」这种情形。 */
        void jump(long seconds) {
            t = t.plusSeconds(seconds);
        }
    }

    private CodeLookupService serviceWithClock(Clock clock) {
        return serviceWithClock(clock, DECADE_BARS, FALLBACK_KLINE_LIMIT);
    }

    /**
     * 「想要多少根、每页最多多少根」都调小的服务——翻页的边界用例要能把这两个数摆在一起。
     *
     * <p>{@code want} 是**总根数**，{@code pageSize} 是**单次外呼的根数**：页数是
     * {@code ceil(want / pageSize)}，不是「随便翻到没有为止」。
     */
    private CodeLookupService serviceWithKlineLimits(int want, int pageSize) {
        return serviceWithClock(() -> FIXED_NOW, want, pageSize);
    }

    private CodeLookupService serviceWithClock(Clock clock, int want, int pageSize) {
        return new CodeLookupService(marketDataClient, altQuoteSource, stockValuationClient,
                offPoolIndexClient, indexPool, valuationService, cache,
                want, pageSize) {
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

    /** 兜底行情给一只。{@code provider} 决定是腾讯那路还是新浪那路。 */
    private void givenFallbackQuote(String code, String name, String price, String pct, String provider) {
        Map<String, MarketDataClient.EtfQuote> one = Map.of(code,
                new MarketDataClient.EtfQuote(code, name, new BigDecimal(price),
                        new BigDecimal(pct), null, new BigDecimal("1200"),
                        null, null, provider));
        if (AltQuoteSource.PROVIDER_SINA.equals(provider)) {
            when(altQuoteSource.fetchSinaQuotes(anyList())).thenReturn(one);
        } else {
            when(altQuoteSource.fetchTencentQuotes(anyList())).thenReturn(one);
        }
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

    /**
     * 按日期区间 stub 腾讯日线：手里有一整段历史，每次调用切出「该区间**末尾**的
     * {@code pageSize} 根」——复刻源在只给 {@code end} 时的真实行为。
     *
     * <p>翻页的用例**必须**用这个而不是「每次回同一批」：回同一批时 {@code tencentBars}
     * 会按「日期数没有增长」判定源忽略了 {@code end} 而收工，第二页根本走不到，
     * 用例就成了在测防死循环那条闸门，而不是在测翻页。
     */
    private void givenTencentPaging(List<PricePositionCalculator.Bar> all, int pageSize) {
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt())).thenAnswer(inv -> {
            LocalDate end = inv.getArgument(1);
            int limit = inv.getArgument(2);
            List<PricePositionCalculator.Bar> inRange = new ArrayList<>();
            for (PricePositionCalculator.Bar b : all) {
                if (end == null || !b.date().isAfter(end)) {
                    inRange.add(b);
                }
            }
            List<PricePositionCalculator.Bar> page = inRange.size() <= limit
                    ? inRange
                    : inRange.subList(inRange.size() - limit, inRange.size());
            return MarketDataClient.KlineOutcome.ok(page, AltQuoteSource.PROVIDER_TENCENT);
        });
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

    /**
     * 池内蛋卷指数 + 蛋卷的 PE 历史**这次没拿到** → 掉回项目库里同口径的已累积行，
     * 并且把「换了来源」写在 `notes` 里。
     *
     * <p>缺档那句要说「**本次**只有项目库累积的 N 行」，**不能**沿用
     * {@link CodeLookupService#DANJUAN_NO_HISTORY}：「蛋卷这次没给到」和「蛋卷没有这个指数」
     * 指向的下一步完全不同（一个重试就有，一个只能换源）。
     */
    @Test
    void aDanjuanPoolIndexFallsBackToTheProjectLibraryWhenTheHistoryEndpointGivesNothing() {
        when(indexPool.byIndexCode("NDX"))
                .thenReturn(danjuanFund("NDX", "NDX", "513100", 1, "纳指100"));
        givenQuotes(row("513100", "纳指100ETF", "1.80", "-0.30", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        // mock 未 stub fetchDanjuanHistory → null（生产不会返回 null，但这条闸门不能省）
        // 项目库里只有最近几行——一天一天攒出来的
        when(valuationService.historyBetween(eq("NDX"), eq(IndexFundPool.METHOD_DANJUAN), any(), any()))
                .thenReturn(List.of(
                        dbRow(TODAY.minusDays(2), "35.0", "87.00"),
                        dbRow(TODAY.minusDays(1), "35.1", "87.20"),
                        dbRow(TODAY, "35.2", "87.32")));

        CodeLookupDTO dto = service().lookup("NDX");

        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.INDEX);
        assertThat(dto.valuation().pePercentile()).isEqualByComparingTo("87.32");
        assertThat(dto.valuation().percentileMethod()).isEqualTo(IndexFundPool.METHOD_DANJUAN);
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("项目库累积"));

        // 「昨」有着落，长档没有——而**没有的原因必须写出来**，不能留一个空格。
        assertThat(cell(dto, "昨").present()).isTrue();
        for (String label : new String[]{"三年", "五年", "十年"}) {
            assertThat(cell(dto, label).present()).as("档位 %s", label).isFalse();
            assertThat(cell(dto, label).status())
                    .as("档位 %s 的原因", label)
                    .contains("项目库累积的 3 行")
                    .contains(TODAY.minusDays(2).toString());
        }
        // 这条路上**没有一档**该引用周频那句话：走的是库里的行，不是蛋卷的周频序列。
        // 两句都说「分位算不出来」但指的方向不同，串了就没人能判断下一步做什么。
        assertThat(dto.valuation().lookbacks()).allSatisfy(c ->
                assertThat(c.status()).isNotEqualTo(CodeLookupService.DANJUAN_WEEKLY_NOTE));
        // 价格八档照常（日线来自代表 ETF）：蛋卷那条路走的是估值，与价格无关
        assertThat(dto.priceLookbacks()).allSatisfy(c -> assertThat(c.present()).isTrue());
    }

    /**
     * 池内蛋卷指数 + 蛋卷给了**周频 PE 历史** → 八档填满，**只有「昨」留空**。
     *
     * <p>「昨」留空是刻意的（用户定的）：周频序列上「最近一次观测」同时就是序列末点（今），
     * 照填会得出一个恒为 {@code +0.00} 的「昨」——把一个「这一档没有观测」写成「没有变化」。
     * 其余七档照填：它们的基线是**另外的**观测点。
     *
     * <p>同时钉住两件事：入参用的是 {@code danjuanCode}（不是项目库的键），
     * 以及这条路上**一次都不读项目库**。
     */
    @Test
    void aDanjuanPoolIndexWithWeeklyPeHistoryFillsEveryBandExceptYesterday() {
        when(indexPool.byIndexCode("NDX"))
                .thenReturn(danjuanFund("NDX", "NDX", "513100", 1, "纳指100"));
        givenQuotes(row("513100", "纳指100ETF", "1.80", "-0.30", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        List<RollingPercentile.Point> weekly = densePePoints(560);   // 周频，约 10.7 年 → 够到十年档
        LocalDate last = weekly.get(weekly.size() - 1).date();
        when(offPoolIndexClient.fetchDanjuanHistory("NDX")).thenReturn(OffPoolIndexClient.Result.ok(
                IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                IndexFundPool.METHOD_DANJUAN, "纳指100", weekly.get(weekly.size() - 1).value(),
                null, last, weekly));

        CodeLookupDTO dto = service().lookup("NDX");

        verify(offPoolIndexClient).fetchDanjuanHistory("NDX");
        // 有历史这条路上**一次都不读项目库**：读它就是在拿另一个数去顶缺档
        verify(valuationService, never()).historyBetween(any(), any(), any(), any());
        assertThat(dto.valuation().historyLength()).isEqualTo(weekly.size());
        assertThat(dto.valuation().source()).isEqualTo(IndexFundPool.SOURCE_DANJUAN);
        assertThat(dto.valuation().percentileMethod()).isEqualTo(IndexFundPool.METHOD_DANJUAN);

        assertThat(cell(dto, "昨").present()).isFalse();
        assertThat(cell(dto, "昨").baseline()).isNull();
        assertThat(cell(dto, "昨").status()).isEqualTo(CodeLookupService.DANJUAN_WEEKLY_NOTE);
        for (String label : new String[]{"周", "月", "半年", "一年", "三年", "五年", "十年"}) {
            assertThat(cell(dto, label).present()).as("档位 %s", label).isTrue();
            assertThat(cell(dto, label).baselineDate()).as("档位 %s 的基线日", label).isNotNull();
        }
        // 分位是**本项目现算**的，这件事必须写在页面上——蛋卷自家那个数差约 1pp
        assertThat(dto.notes()).anySatisfy(n ->
                assertThat(n).contains("周频").contains("现算"));
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
        // 同一只指数、同一个源自己回「没有这个代码的历史」——两支接口对同一个代码给出的
        // 空数组**分不开**，所以这里只能降级，**不能**变成 404。
        when(offPoolIndexClient.fetchDanjuanHistory("HSI")).thenReturn(OffPoolIndexClient.Result.failed(
                IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                "蛋卷没有 HSI 的 PE 历史（该源不是每个指数都有）"));

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

    /**
     * 池外蛋卷指数**有**周频 PE 历史 → 七档有值、「昨」按周频留空。
     *
     * <p>这条路一次点击确实要打两次蛋卷（{@code dj} 清单定「这个代码存不存在」+ 这一支取历史），
     * 是有意接受的：清单那一支还负责 404 的判定，合并掉它那个语义就没了。
     */
    @Test
    void aDanjuanOffPoolIndexWithWeeklyHistoryGetsSevenBandsAndNoYesterday() {
        when(offPoolIndexClient.fetch(any())).thenReturn(OffPoolIndexClient.Result.ok(
                IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                IndexFundPool.METHOD_DANJUAN, "恒生指数", new BigDecimal("11.1"),
                new BigDecimal("42.00"), TODAY, List.of()));
        List<RollingPercentile.Point> weekly = densePePoints(560);
        LocalDate last = weekly.get(weekly.size() - 1).date();
        when(offPoolIndexClient.fetchDanjuanHistory("HSI")).thenReturn(OffPoolIndexClient.Result.ok(
                IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                IndexFundPool.METHOD_DANJUAN, "恒生指数", weekly.get(weekly.size() - 1).value(),
                null, last, weekly));

        CodeLookupDTO dto = service().lookup("HSI");

        // 入参是**这一支要的代码**（形态代码本身），不是别的东西
        verify(offPoolIndexClient).fetchDanjuanHistory("HSI");
        assertThat(dto.valuation().historyLength()).isEqualTo(weekly.size());
        assertThat(dto.valuation().peTtm()).isEqualByComparingTo("11.1");
        // 分位改用**现算**的，不是清单那支报的 42.00：两个数并排出现会变成「同一只指数两个分位」
        assertThat(dto.valuation().pePercentile()).isNotEqualByComparingTo("42.00");
        assertThat(cell(dto, "昨").present()).isFalse();
        assertThat(cell(dto, "昨").status()).isEqualTo(CodeLookupService.DANJUAN_WEEKLY_NOTE);
        for (String label : new String[]{"周", "月", "半年", "一年", "三年", "五年", "十年"}) {
            assertThat(cell(dto, label).present()).as("档位 %s", label).isTrue();
        }
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

    /**
     * 东财挂了，行情从腾讯来，来源写在结果里。
     *
     * <p><b>用的是一场外基金（512880），不是个股。</b>这条用例原先拿 {@code 300274} 当样本，
     * 而那是靠 mock 才成立的：真跑起来 {@code fetchTencentQuotes} 会先过
     * {@code symbolsOf}，个股不在白名单里**一个请求都不发**，腾讯根本不可能给出这只票的价。
     * 测一个生产里到不了的场景，等于给兜底链一个假的绿灯。
     */
    @Test
    void whenEastmoneyIsUnreachableTheQuoteComesFromTencentAndTheSourceIsWrittenOut() {
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new MarketDataException("行情批量接口全部失败—— 第 1 批：连接超时"));
        givenFallbackQuote("512880", "证券ETF", "1.08", "0.93", AltQuoteSource.PROVIDER_TENCENT);
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(stockValuationClient.fetchHistory("512880")).thenReturn(
                StockValuationClient.History.failed("512880", "估值分析源不可达"));

        CodeLookupDTO dto = service().lookup("512880");

        assertThat(dto.quote().provider()).isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
        // 用了兜底源这件事必须写出来：同一天两个源的价可能不一样，而页面只显示一个数。
        // 来源既在 providerLabel 里（给每一格标出处），也在 notes 里（说明为什么换了源）。
        assertThat(dto.quote().providerLabel()).contains("腾讯").contains("兜底");
        assertThat(dto.quote().price()).isEqualByComparingTo("1.08");
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("兜底源"));
        // 注意没有走「按池外指数重试」那条路——那是因为**源答了**，不是因为源挂了。
        assertThat(dto.kind()).isEqualTo(CodeLookupDTO.Kind.FUND);
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

    /**
     * 冷却期内**不打东财**，但**照常出数**——这是本次改动的要点。
     *
     * <p>原来这条用例断言「一律 429 且谁都不碰」，前提是「被东财限流 = 什么都取不到」。
     * 线上实测把这个前提推翻了：被封的只有 push2 一族（腾讯/新浪/估值源全是好的），
     * 而那道早退正是用户截图里整页打不开的原因。
     */
    @Test
    void whileEastmoneyIsInCooldownAnEtfIsStillServedFromTheFallbackSource() {
        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, 10);
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenFallbackQuote("510300", "沪深300ETF", "4.05", "0.52",
                AltQuoteSource.PROVIDER_TENCENT);
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(tradingBars(800, TODAY),
                        AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        assertThat(dto.quote().provider()).isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
        assertThat(dto.quote().price()).isEqualByComparingTo("4.05");
        // **东财一次都没被碰**——「冷却期内不再外呼」现在成立的形式就长这样：
        // 不是入口早退，而是取数处根本不调用 push2/push2his。
        verifyNoInteractions(marketDataClient);
        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isTrue();
    }

    /**
     * 冷却期内查**个股**：兜底源表示不了这个码，于是没有可用的取数路径 → 429。
     *
     * <p>关键是**不能是 404**。东财被限流时返回 null 会被 {@code doLookup} 读成
     * 「池外指数」，个股就变成「查不到代码 300274」——换个时间点结果不同，页面上看不出来。
     */
    @Test
    void whileEastmoneyIsInCooldownAStockGets429AndNothingIsDialled() {
        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, 10);

        CodeLookupService svc = service();
        assertThatThrownBy(() -> svc.lookup("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .hasMessageContaining("冷却")
                .hasMessageContaining("分钟")
                .isNotInstanceOf(CodeLookupService.CodeNotFoundException.class);

        verifyNoInteractions(marketDataClient, offPoolIndexClient);
        // 表示不了的代码**一个兜底请求都不发**：那是「没问」，不是「问了没有」
        verifyNoInteractions(altQuoteSource);
    }

    /**
     * 东财被限流 + 兜底也不认这个码：**不是 404**，因为根本没人问出「这码不存在」。
     *
     * <p>★ 这是整次改动风险最高的那一格：只要漏判一次，被限流的个股就会变成
     * 「查不到代码」——比报错更难查，而且过十分钟自己又好了。
     */
    @Test
    void aThrottledQuoteIsRaisedAndLocksTheSourceInsteadOfBeingRetried() {
        when(marketDataClient.fetchQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("被限流了", null));

        CodeLookupService svc = service();
        assertThatThrownBy(() -> svc.lookup("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .isNotInstanceOf(CodeLookupService.CodeNotFoundException.class);

        // 只把 429 返给前端而不锁住自己，用户点一次「再试一次」就又是一次真实外呼
        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isTrue();
        verify(marketDataClient, never()).fetchKline(anyString(), anyInt());
    }

    /** 东财限流后**根本没被再问过日线**：它自己刚把冷却打开，日线步骤必须复查。 */
    @Test
    void aThrottledQuoteStopsTheKlineStepFromDiallingEastmoneyAgain() {
        when(marketDataClient.fetchQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("被限流了", null));
        // 个股：兜底日线也不认这个码，所以整页只能报 429
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.failed("300274", "估值分析源不可达"));

        assertThatThrownBy(() -> service().lookup("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);

        verify(marketDataClient, never()).fetchKline(anyString(), anyInt());
        verify(altQuoteSource, never()).fetchTencentKline(anyString(), any(), anyInt());
    }

    /** 东财日线被限流：不再让整页失败，而是记冷却 + 降级；ETF 上还看得出「价格位置」没了。 */
    @Test
    void aThrottledKlineEntersCooldownAndOnlyDegradesTheLongBands() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        givenFallbackQuote("510300", "沪深300ETF", "4.05", "0.52",
                AltQuoteSource.PROVIDER_TENCENT);
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.throttled("行情源限流，价格位置未能更新"));
        // 腾讯日线也不给 → 连兜底都没有
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.failed("腾讯也没给到",
                        AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isTrue();
        // 价格与估值照常，只有价格八档与价格位置降级——而不是整页 429
        assertThat(dto.quote().price()).isEqualByComparingTo("4.05");
        assertThat(dto.position().available()).isFalse();
        assertThat(priceCell(dto, "一年").status()).contains("未取到日线");
    }

    // ==================== 分派矩阵 ====================

    /** 东财明确「没有这只标的」+ 兜底问了也没有 → 才允许按池外指数重解释（{@code 000985}）。 */
    @Test
    void onlyAnExplicitAbsentAnswerUnlocksTheOffPoolReinterpretation() {
        givenQuotes();   // 成功、没有这一行 = 明确回答「没有」
        // 000985 是深市码段，兜底源表示不了 → 压根不问，于是「没问」不会被读成「问了没有」
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));
        givenKline(tradingBars(DECADE_BARS, TODAY));

        assertThat(service().lookup("000985").kind()).isEqualTo(CodeLookupDTO.Kind.OFF_POOL_INDEX);
        verifyNoInteractions(altQuoteSource);
    }

    /** 东财明确「没有」，兜底被打回来 → 仍旧按池外指数解释：**东财是权威**。 */
    @Test
    void anExplicitAbsentAnswerOutranksAThrottledFallback() {
        givenQuotes();
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("腾讯也在限流", null));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));
        givenKline(tradingBars(DECADE_BARS, TODAY));

        assertThat(service().lookup("159915").kind()).isEqualTo(CodeLookupDTO.Kind.OFF_POOL_INDEX);
    }

    /** 东财限流 + 可表示的基金 + 兜底也「问了没有」→ **429，绝不是 404**。 */
    @Test
    void aThrottledEastmoneyPlusAnAbsentFallbackIs429Not404() {
        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, 10);
        // 159915 是深市基金，腾讯/新浪能表示它，两边都回「没有这一行」
        when(indexPool.byEtfCode("159915")).thenReturn(null);

        assertThatThrownBy(() -> service().lookup("159915"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .isNotInstanceOf(CodeLookupService.CodeNotFoundException.class);

        verifyNoInteractions(marketDataClient, offPoolIndexClient);
    }

    /** 东财非限流失败 + 兜底全挂 → 503，且文案**不说**「三家都不可达」（对个股是假的）。 */
    @Test
    void whenEastmoneyFailsAndTheFallbacksCannotRepresentTheCodeTheMessageIsHonest() {
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new MarketDataException("行情批量接口全部失败"));
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenThrow(new RuntimeException("超时"));
        when(altQuoteSource.fetchSinaQuotes(anyList())).thenThrow(new RuntimeException("超时"));

        assertThatThrownBy(() -> service().lookup("300274"))
                .isInstanceOf(MarketDataException.class)
                .isNotInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .hasMessageContaining("东财本次没给出结果")
                // 腾讯/新浪**不是不可达**，是白名单不认这个码——原话术是假的
                .hasMessageNotContaining("三家都不可达");
    }

    /** 腾讯行情限流、新浪接着顶上：**冷却的是一个源，不是整条兜底链**。 */
    @Test
    void aThrottledTencentFallsThroughToSinaInsteadOfEndingTheChain() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new MarketDataException("东财行情不可用"));
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("腾讯被限流", null));
        when(altQuoteSource.fetchSinaQuotes(anyList())).thenReturn(Map.of("510300",
                new MarketDataClient.EtfQuote("510300", "沪深300ETF", new BigDecimal("4.05"),
                        new BigDecimal("0.52"), null, new BigDecimal("1200"),
                        null, null, AltQuoteSource.PROVIDER_SINA)));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        assertThat(dto.quote().provider()).isEqualTo(AltQuoteSource.PROVIDER_SINA);
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_SINA)).isFalse();
    }

    /** 兜底两路都限流、东财又是非限流失败 → 429，报的是**兜底源**的剩余时间。 */
    @Test
    void whenBothFallbacksAreThrottledTheWaitIsReportedForTheFallbackSource() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        when(marketDataClient.fetchQuotes(anyList()))
                .thenThrow(new MarketDataException("东财行情不可用"));
        when(altQuoteSource.fetchTencentQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("腾讯被限流", null));
        when(altQuoteSource.fetchSinaQuotes(anyList())).thenThrow(
                new MarketDataException.MarketDataRateLimitedException("新浪被限流", null));

        assertThatThrownBy(() -> service().lookup("510300"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class)
                .hasMessageContaining("冷却");

        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_SINA)).isTrue();
        // 东财这次是**非限流**失败，没被锁——锁的是真被打回来的那两家
        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isFalse();
    }

    // ==================== 兜底日线 ====================

    /** 东财没有日线时用腾讯的，且**每一页的根数都夹在腾讯的硬上限内**（2600 会被直接拒）。 */
    @Test
    void theFallbackKlineIsAskedForExactlyTheTencentCapNotTheEastmoneyLimit() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        givenTencentPaging(tradingBars(DECADE_BARS, TODAY), 800);
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        // 传 2600 会拿到 {"msg":"param error","data":[]}——必须夹到 800，不是「随便挑个数」。
        // **每一页**都夹（`end` 逐页往回推，根数不变），所以这里不能用「恰好一次」。
        verify(altQuoteSource, atLeastOnce()).fetchTencentKline(eq("510300"), any(), eq(800));
        verify(altQuoteSource, never()).fetchTencentKline(anyString(), any(),
                intThat(n -> n > AltQuoteSource.TENCENT_KLINE_MAX_ROWS));
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n).contains("日线取自").contains("兜底"));
        assertThat(dto.position().available()).isTrue();
        // 翻页之后**十年这一档够得到**：2600 根按 800 翻 4 页（末页 200 根）
        assertThat(priceCell(dto, "十年").present()).isTrue();
    }

    /**
     * 翻到源的上限还是不够（这里只给到 800 根）时，`status` 必须说清是**源的上限**，
     * 不是这只标的上市时间短。
     *
     * <p>这是 {@code LookbackCalculator} 第三个参数的用途所在；传 null 会让它退回通用文案，
     * 把下一步指向错的地方。文案用**实际根数与最早交易日**拼，不硬写「3 年」。
     *
     * <p>夹具刻意每次回**同一页**（源忽略 {@code end} 的情形）：翻页循环按「日期数没有增长」
     * 收工，于是这里停在第 2 次而不外呼第 3 次——见
     * {@code whenTheSourceIgnoresTheEndDateThePagingStopsInsteadOfLoopingForever}。
     */
    @Test
    void whenTheFallbackKlineIsShortTheEmptyBandsBlameTheSourceNotTheListedHistory() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        List<PricePositionCalculator.Bar> short800 = tradingBars(800, TODAY);
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(short800, AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        String tenYear = priceCell(dto, "十年").status();
        assertThat(tenYear).contains("800").contains("腾讯").contains("未确认");
        assertThat(tenYear).doesNotContain("历史不足");
        // 最早的交易日要写出来，这样「为什么不够」是可核对的
        assertThat(tenYear).contains(short800.get(0).date().toString());
    }

    // ==================== 兜底日线的翻页 ====================

    /**
     * 按日期区间往回翻：2600 根要 4 页（800/800/800/200），拼起来**没有重复日期**。
     *
     * <p>页边界那天两边都会给（{@code end = 上一页最早 − 1 天} 也是服务端的闭区间），
     * 所以去重不是可选项——重复会让「十年」那一档的基线偏一天，而页面上看不出来。
     */
    @Test
    void theFallbackKlineIsPagedBackwardsUntilTheAskedForCountIsReached() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        List<PricePositionCalculator.Bar> full = tradingBars(DECADE_BARS, TODAY);
        givenTencentPaging(full, 800);
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        // 4 页：2600 根按 800 切，第 4 页只剩 200 根，拿到就被「已够要的根数」收工
        verify(altQuoteSource, times(4)).fetchTencentKline(eq("510300"), any(), eq(800));
        assertThat(dto.position().barCount()).isEqualTo(DECADE_BARS);
        assertThat(priceCell(dto, "十年").present()).isTrue();
        // 「今」还得是最近那一根：翻页是从最近往回走的，顺序不能反过来
        assertThat(dto.position().lastTradeDate()).isEqualTo(TODAY.toString());
    }

    /**
     * 源忽略了 {@code end}（每次回同一批）→ **恰好两次就停**。
     *
     * <p>这是翻页唯一的无限外呼风险点：没有「日期数没有增长」这条闸门，循环会一直打同一个
     * 接口直到页数上限，而页数上限是按根数算的、不是按「有进展」算的。
     */
    @Test
    void whenTheSourceIgnoresTheEndDateThePagingStopsInsteadOfLoopingForever() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(tradingBars(800, TODAY),
                        AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        // 第 1 页拿到 800 根 → 没够 → 第 2 页回同一批 → 日期数没增长 → 停
        verify(altQuoteSource, times(2)).fetchTencentKline(eq("510300"), any(), anyInt());
        assertThat(dto.position().barCount()).isEqualTo(800);
    }

    /** 一页就够要的根数时，**只发一页**：少一次外呼，也就少一分被打回来的机会。 */
    @Test
    void thePagingStopsAsSoonAsTheAskedForCountIsReached() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        givenTencentPaging(tradingBars(DECADE_BARS, TODAY), 800);
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        // 只想要 500 根：单页就是 min(想 500, 兜底上限 800, 源上限 800) = 500，一页就够
        CodeLookupDTO dto = serviceWithKlineLimits(500, 800).lookup("510300");

        verify(altQuoteSource, times(1)).fetchTencentKline(eq("510300"), any(), eq(500));
        assertThat(dto.position().barCount()).isEqualTo(500);
    }

    /**
     * 第 2 页被打回来 → 记腾讯的冷却，**但第 1 页那 800 根要留着**。
     *
     * <p>丢掉它们等于把一次真实拿到的连续曲线说成「一根都没有」；它们是从最近往回数的
     * 一段，每一根都对着真实日期，不是半条假曲线。冷却期内连第 3 页都不发（红线）。
     */
    @Test
    void aThrottledSecondPageKeepsTheFirstPageAndStopsAsking() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        List<PricePositionCalculator.Bar> first = tradingBars(800, TODAY);
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(first, AltQuoteSource.PROVIDER_TENCENT))
                .thenReturn(MarketDataClient.KlineOutcome.throttled(
                        "腾讯日线被限流", AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        verify(altQuoteSource, times(2)).fetchTencentKline(eq("510300"), any(), anyInt());
        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();
        // 第 1 页的 800 根照用：价格位置算得出来，只是长档按**实际根数**说明
        assertThat(dto.position().available()).isTrue();
        assertThat(dto.position().barCount()).isEqualTo(800);
        assertThat(priceCell(dto, "十年").status()).contains("800");
    }

    /** 腾讯日线在冷却中 → **连问都不问**：同一个域名刚被打回来，不能立刻再打。 */
    @Test
    void theFallbackKlineIsSkippedWhenTencentIsAlreadyInCooldown() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT, 10);
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        verify(altQuoteSource, never()).fetchTencentKline(anyString(), any(), anyInt());
        assertThat(dto.position().available()).isFalse();
    }

    /** 腾讯日线返回 null（mock 未 stub）→ 不 NPE，老实降级。 */
    @Test
    void aNullFallbackKlineIsHandledInsteadOfBlowingUp() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt())).thenReturn(null);
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupDTO dto = service().lookup("510300");

        assertThat(dto.position().available()).isFalse();
        assertThat(priceCell(dto, "十年").status()).contains("未取到日线");
    }

    /** 腾讯日线也被限流 → 记腾讯的冷却（不是东财的：东财这次只是没给到）。 */
    @Test
    void aThrottledFallbackKlineLocksTencentNotEastmoney() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线不可用"));
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.throttled("腾讯日线被限流", AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        service().lookup("510300");

        assertThat(cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)).isTrue();
        assertThat(cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)).isFalse();
    }

    /** 个股的失败原因要**保留东财那句**：腾讯对个股固定说不认这个前缀，写上去会莫名其妙。 */
    @Test
    void aStockKeepsTheEastmoneyReasonInsteadOfTencentSayingItCannotReadThePrefix() {
        givenQuotes(row("300274", "阳光电源", "82.49", "2.98", "15.57"));
        when(stockValuationClient.fetchHistory("300274")).thenReturn(
                StockValuationClient.History.failed("300274", "估值分析源不可达"));
        when(marketDataClient.fetchKline(anyString(), anyInt())).thenReturn(
                MarketDataClient.KlineOutcome.failed("东财日线接口返回了空数据"));

        CodeLookupDTO dto = service().lookup("300274");

        verify(altQuoteSource, never()).fetchTencentKline(anyString(), any(), anyInt());
        assertThat(dto.notes()).anySatisfy(n -> assertThat(n)
                .contains("东财日线接口返回了空数据")
                .doesNotContain("不认"));
    }

    // ==================== 冷却期的连点 ====================

    /**
     * 冷却期内连点两次 ETF：**东财一次都不打**，每一次都走兜底。
     *
     * <p>降级结果不入缓存（`degrade()` 同时写 `degradations`），所以这里**预期**两次都外呼兜底源。
     * 页面上没有自动重试，单次两次外呼，不加深东财的封禁。要收紧得给「冷却期的兜底结果」
     * 单独一个短 TTL 缓存——本次不做，但这条用例把现状钉住，免得被误当成「缓存失效了」。
     */
    @Test
    void twoClicksDuringACooldownDialTheFallbackTwiceAndEastmoneyNotAtAll() {
        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, 10);
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenFallbackQuote("510300", "沪深300ETF", "4.05", "0.52",
                AltQuoteSource.PROVIDER_TENCENT);
        when(altQuoteSource.fetchTencentKline(eq("510300"), any(), anyInt()))
                .thenReturn(MarketDataClient.KlineOutcome.ok(tradingBars(800, TODAY),
                        AltQuoteSource.PROVIDER_TENCENT));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupService svc = service();
        assertThat(svc.lookup("510300").fromCache()).isFalse();
        assertThat(svc.lookup("510300").fromCache()).isFalse();

        verify(altQuoteSource, times(2)).fetchTencentQuotes(anyList());
        verifyNoInteractions(marketDataClient);
    }

    /**
     * 冷却**之前**存下的成功结果，在冷却期内点击仍然零外呼。
     *
     * <p>这是删掉入口早退之后**唯一**还成立的「冷却期内零外呼」路径，所以必须钉住。
     */
    @Test
    void aResultCachedBeforeTheCooldownIsServedWithoutDiallingAnything() {
        when(indexPool.byEtfCode("510300"))
                .thenReturn(csindexFund("SH000300", "000300", "510300", 1, "沪深300"));
        givenQuotes(row("510300", "沪深300ETF", "4.05", "0.52", null));
        givenKline(tradingBars(DECADE_BARS, TODAY));
        when(offPoolIndexClient.fetch(any())).thenReturn(csindexOk(densePePoints(580)));

        CodeLookupService svc = service();
        assertThat(svc.lookup("510300").fromCache()).isFalse();   // 东财健康时取一次

        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY, 10);

        CodeLookupDTO second = svc.lookup("510300");
        assertThat(second.fromCache()).isTrue();
        verifyNoInteractions(altQuoteSource);
        verify(marketDataClient, times(1)).fetchQuotes(anyList());
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

        // 时钟**每读一次前进一秒**，并且两次点击之间再拨 2 分钟（仍在 TTL 内）。
        // 两件事各拦一种回归：
        //   · 走动的秒针拦「一次查询读了两次钟」——坏实现里 DTO 与缓存条目会差一秒；
        //   · 拨表拦「命中缓存时改用 now() 重新盖章」——不拨的话两种实现印出同一个字符串。
        // 固定时钟对前者是瞎的，第一件事才是这条用例真正要守住的不变量。
        SteppingClock clock = new SteppingClock(FIXED_NOW);
        CodeLookupService svc = serviceWithClock(clock);

        CodeLookupDTO first = svc.lookup("510300");
        clock.jump(120);
        CodeLookupDTO second = svc.lookup("510300");

        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        // 命中缓存的那一次**不能**再外呼：连点不该被放大成对东财的压测
        verify(marketDataClient, times(1)).fetchKline(anyString(), anyInt());
        verify(offPoolIndexClient, times(1)).fetch(any());
        // 缓存的时刻要写出来，不能假装「是现在取的」
        assertThat(second.snapshotAt()).isEqualTo(first.snapshotAt());
        // 只比 first/second 还不够——两边都取「现在」也能相等。钉住具体值，
        // 这条才是真的在拦「命中缓存时改用 now() 重新盖章」。
        assertThat(second.snapshotAt()).startsWith("2026-09-30T10:00");
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