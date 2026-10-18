package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.DataStatus;
import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * P4 的固定事实快照。
 *
 * <p>日期是写死的：{@link #TODAY} = 2026-10-18，最后一根日线 = 2026-10-16。
 * 所有测试用同一个「今天」，分数、区间、情景才能逐字段复现。
 */
public final class AnalysisFixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    public static final LocalDate PRICE_AS_OF = LocalDate.of(2026, 10, 16);
    private static final BigDecimal YI = BigDecimal.valueOf(100_000_000L);

    private AnalysisFixtures() {
    }

    // ------------------------------------------------------------------
    // 个股
    // ------------------------------------------------------------------

    public static FactsSnapshot healthyStock() {
        return stock("示例科技", "600519", "SH", "酿酒行业", "GENERAL",
                bd("20"), bd("14.5"), LocalDate.of(2015, 1, 5), PRICE_AS_OF);
    }

    /** 当前 PE 为负：应触发 LOSS_MAKING，估值锚退回 PB。 */
    static FactsSnapshot lossStock() {
        return stock("亏损科技", "300001", "SZ", "软件服务", "GENERAL",
                bd("20"), bd("-3"), LocalDate.of(2015, 1, 5), PRICE_AS_OF);
    }

    static FactsSnapshot stStock() {
        return stock("ST示例", "600002", "SH", "综合", "GENERAL",
                bd("20"), bd("14.5"), LocalDate.of(2015, 1, 5), PRICE_AS_OF);
    }

    static FactsSnapshot bankStock() {
        return stock("示例银行", "600036", "SH", "银行", "FINANCIAL",
                bd("20"), bd("14.5"), LocalDate.of(2010, 1, 5), PRICE_AS_OF);
    }

    static FactsSnapshot realEstateStock() {
        return stock("示例地产", "000002", "SZ", "房地产", "REAL_ESTATE",
                bd("20"), bd("14.5"), LocalDate.of(2010, 1, 5), PRICE_AS_OF);
    }

    static FactsSnapshot newStock() {
        return stock("次新股", "301999", "SZ", "电子元件", "GENERAL",
                bd("20"), bd("14.5"), TODAY.minusDays(30), PRICE_AS_OF);
    }

    static FactsSnapshot stalePriceStock() {
        return stock("停牌股", "600003", "SH", "综合", "GENERAL",
                bd("20"), bd("14.5"), LocalDate.of(2015, 1, 5), TODAY.minusDays(10));
    }

    /** 负 PE 且 PB 历史也不可用：区间必须 withheld。 */
    static FactsSnapshot noValuationStock() {
        FactsSnapshot base = lossStock();
        return new FactsSnapshot(base.inputCode(), base.instrument(), base.identity(),
                base.quote(), base.prices(),
                FactsSnapshot.Domain.empty("本地没有估值历史"),
                base.indexValuation(), base.financials(), base.moneyflow(),
                base.indexProfile(), base.industry(), base.events(),
                false, 0, true, "noval1", "2026-10-18T10:00:00");
    }

    public static FactsSnapshot emptyStock() {
        return new FactsSnapshot("600009", InstrumentCode.stock("600009", "SH"),
                new FactsSnapshot.Identity("600009", "STOCK", "SH", null, null, null,
                        null, null, "ACTIVE", null),
                FactsSnapshot.Domain.empty("没有行情"),
                FactsSnapshot.Domain.empty("没有日线"),
                FactsSnapshot.Domain.empty("没有估值"),
                FactsSnapshot.Domain.notApplicable("个股没有指数估值"),
                FactsSnapshot.Domain.empty("没有财务"),
                FactsSnapshot.Domain.empty("没有资金流"),
                FactsSnapshot.Domain.notApplicable("个股没有指数画像"),
                FactsSnapshot.Domain.notApplicable("个股没有行业权重"),
                FactsSnapshot.Domain.empty("没有事件"),
                true, 0, true, "empty1", "2026-10-18T10:00:00");
    }

    private static FactsSnapshot stock(String name, String code, String market, String industry,
                                       String sectorProfile, BigDecimal price, BigDecimal peNow,
                                       LocalDate listDate, LocalDate quoteAsOf) {
        InstrumentCode instrument = InstrumentCode.stock(code, market);
        List<WarehouseRows.PriceBar> prices = risingPrices(price, 300, PRICE_AS_OF);
        List<WarehouseRows.ValuationPoint> valuations = valuations(PRICE_AS_OF, peNow);
        List<WarehouseRows.MoneyFlow> flows = flows(prices, 30);
        List<WarehouseRows.FinancialReport> reports = annualReports();

        FactsSnapshot.Quote quote = new FactsSnapshot.Quote("eastmoney", "东方财富",
                price, bd("1.2"), bd("2").multiply(YI), bd("2"), bd("100").multiply(YI),
                null, null, null, quoteAsOf);
        FactsSnapshot.Identity identity = new FactsSnapshot.Identity(code, "STOCK", market,
                name, industry, null, null, listDate, "ACTIVE", sectorProfile);
        return new FactsSnapshot(code, instrument, identity,
                FactsSnapshot.Domain.ok(quote, quoteAsOf.toString()),
                FactsSnapshot.Domain.ok(prices, PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.ok(valuations, PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.notApplicable("个股没有指数估值"),
                FactsSnapshot.Domain.ok(reports, "2026-06-30"),
                FactsSnapshot.Domain.ok(flows, PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.notApplicable("个股没有指数画像"),
                FactsSnapshot.Domain.notApplicable("个股没有行业权重"),
                FactsSnapshot.Domain.ok(List.of(), null),
                false, 0, true, "stock-" + code, "2026-10-18T10:00:00");
    }

    // ------------------------------------------------------------------
    // ETF / 指数
    // ------------------------------------------------------------------

    public static FactsSnapshot etf() {
        return indexLike("510300", "SH", "ETF", "沪深300ETF", bd("4.000"), "000300");
    }

    public static FactsSnapshot index() {
        return indexLike("000300", "SH", "INDEX", "沪深300", bd("3800"), "000300");
    }

    private static FactsSnapshot indexLike(String code, String market, String assetType,
                                           String name, BigDecimal price, String indexCode) {
        InstrumentCode instrument = new InstrumentCode(assetType, code, market);
        List<WarehouseRows.PriceBar> prices = risingPrices(price, 300, PRICE_AS_OF);
        FactsSnapshot.Quote quote = new FactsSnapshot.Quote("eastmoney", "东方财富",
                price, bd("0.8"), bd("5").multiply(YI), bd("3"), null,
                null, null, null, PRICE_AS_OF);
        FactsSnapshot.Identity identity = new FactsSnapshot.Identity(code, assetType, market,
                name, null, null, indexCode, LocalDate.of(2012, 5, 28), "ACTIVE", "GENERAL");
        FactsSnapshot.IndexValuation valuation = new FactsSnapshot.IndexValuation(
                "csindex", "中证官网（中证口径）", "csindex-pe-10y",
                bd("11.2"), bd("30"), bd("10"), bd("11"), bd("12"), bd("40"),
                PRICE_AS_OF, 2500, LocalDate.of(2016, 1, 4), PRICE_AS_OF);
        WarehouseRows.IndexProfile profile = new WarehouseRows.IndexProfile(indexCode,
                LocalDate.of(2026, 9, 30), name, 300, bd("22"), bd("12"), bd("2.5"),
                bd("50000").multiply(YI), null, "csindex", LocalDateTime.of(2026, 9, 30, 18, 0));
        List<WarehouseRows.IndustryWeight> industry = List.of(
                weight(indexCode, "银行", bd("20")),
                weight(indexCode, "电子", bd("25")),
                weight(indexCode, "食品饮料", bd("15")));
        return new FactsSnapshot(code, instrument, identity,
                FactsSnapshot.Domain.ok(quote, PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.ok(prices, PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.notApplicable("指数/ETF 估值在指数估值域"),
                FactsSnapshot.Domain.ok(valuation, PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.notApplicable("没有公司财务报表"),
                FactsSnapshot.Domain.notApplicable("不提供个股资金流"),
                FactsSnapshot.Domain.ok(profile, "2026-09-30"),
                FactsSnapshot.Domain.ok(industry, "2026-09-30"),
                FactsSnapshot.Domain.ok(List.of(), null),
                false, 0, true, assetType + "-" + code, "2026-10-18T10:00:00");
    }

    private static WarehouseRows.IndustryWeight weight(String indexCode, String name,
                                                       BigDecimal value) {
        return new WarehouseRows.IndustryWeight(indexCode, LocalDate.of(2026, 9, 30),
                "BK" + name.hashCode(), name, value, "csindex",
                LocalDateTime.of(2026, 9, 30, 18, 0));
    }

    // ------------------------------------------------------------------
    // 序列
    // ------------------------------------------------------------------

    static List<WarehouseRows.PriceBar> risingPrices(BigDecimal endPrice, int count,
                                                     LocalDate endDate) {
        List<LocalDate> dates = tradingDays(endDate, count);
        BigDecimal start = endPrice.multiply(bd("0.5"));
        BigDecimal step = endPrice.subtract(start)
                .divide(BigDecimal.valueOf(Math.max(1, count - 1)), 6, java.math.RoundingMode.HALF_UP);
        List<WarehouseRows.PriceBar> bars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BigDecimal close = start.add(step.multiply(BigDecimal.valueOf(i)))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            bars.add(new WarehouseRows.PriceBar("x", dates.get(i), "QFQ",
                    close, close.multiply(bd("1.005")).setScale(4, java.math.RoundingMode.HALF_UP),
                    close.multiply(bd("0.995")).setScale(4, java.math.RoundingMode.HALF_UP),
                    close, BigDecimal.valueOf(1_000_000), bd("2").multiply(YI), bd("2"),
                    bd("0.5"), "eastmoney", LocalDateTime.of(2026, 10, 16, 18, 0)));
        }
        return bars;
    }

    /**
     * 估值历史：PE 在 10–20 之间均匀分布，最后一根按 {@code lastPe} 覆盖。
     *
     * <p>取 946 个点（间隔两天）= 5 年多，满足「≥500 点且跨度 ≥3 年」。
     * 896..945 的模 100 为 45，让 q25/q40 落在 12.5/14 附近。
     */
    private static List<WarehouseRows.ValuationPoint> valuations(LocalDate endDate,
                                                                 BigDecimal lastPe) {
        int count = 946;
        List<WarehouseRows.ValuationPoint> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int slot = i % 100;
            BigDecimal pe = bd("10").add(bd("10")
                    .multiply(BigDecimal.valueOf(slot))
                    .divide(bd("100"), 4, java.math.RoundingMode.HALF_UP));
            BigDecimal pb = bd("1").add(bd("2")
                    .multiply(BigDecimal.valueOf(slot))
                    .divide(bd("100"), 4, java.math.RoundingMode.HALF_UP));
            if (i == count - 1) {
                pe = lastPe;
            }
            LocalDate date = endDate.minusDays(2L * (count - 1 - i));
            points.add(new WarehouseRows.ValuationPoint("x", date, pe, pb, null,
                    bd("1.5"), null, "none", "eastmoney-valueanalysis",
                    LocalDateTime.of(2026, 10, 16, 18, 0)));
        }
        return points;
    }

    private static List<WarehouseRows.MoneyFlow> flows(List<WarehouseRows.PriceBar> prices,
                                                       int count) {
        List<WarehouseRows.MoneyFlow> out = new ArrayList<>();
        for (int i = Math.max(0, prices.size() - count); i < prices.size(); i++) {
            out.add(new WarehouseRows.MoneyFlow("x", prices.get(i).tradeDate(),
                    bd("20000000"), null, null, null, null, bd("1.2"),
                    "eastmoney", LocalDateTime.of(2026, 10, 16, 18, 0)));
        }
        return out;
    }

    private static List<WarehouseRows.FinancialReport> annualReports() {
        List<WarehouseRows.FinancialReport> reports = new ArrayList<>();
        reports.add(report("2026-06-30", "Q2", bd("5.0"), bd("18"), bd("0.80"), bd("22")));
        reports.add(report("2025-12-31", "ANNUAL", bd("4.0"), bd("16"), bd("0.60"), bd("20")));
        reports.add(report("2024-12-31", "ANNUAL", bd("3.2"), bd("12"), bd("0.45"), bd("15")));
        reports.add(report("2022-12-31", "ANNUAL", bd("2.2"), bd("8"), bd("0.30"), bd("10")));
        return reports;
    }

    private static WarehouseRows.FinancialReport report(String date, String type,
                                                        BigDecimal revenueYi,
                                                        BigDecimal revenueYoy,
                                                        BigDecimal profitYi,
                                                        BigDecimal profitYoy) {
        BigDecimal revenue = revenueYi.multiply(YI);
        BigDecimal profit = profitYi.multiply(YI);
        return new WarehouseRows.FinancialReport("600519", LocalDate.parse(date), type,
                LocalDate.parse(date).plusDays(40), LocalDate.parse(date).plusDays(40),
                revenue, revenueYoy, profit, profitYoy,
                bd("14"), bd("35"), bd("16"), profit.multiply(bd("1.1")),
                bd("120").multiply(YI), bd("60").multiply(YI), bd("60").multiply(YI),
                bd("50").multiply(YI), bd("30").multiply(YI),
                bd("0.5").multiply(YI), bd("10").multiply(YI),
                bd("2").multiply(YI), bd("0.8").multiply(YI),
                "eastmoney", LocalDateTime.of(2026, 8, 20, 18, 0));
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

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /** 构造一条公告事件；{@code category=NOTICE} 才会进入事件调整。 */
    static FactsSnapshot withEvent(FactsSnapshot base, String title, LocalDate published) {
        WarehouseRows.Event event = new WarehouseRows.Event(
                base.instrument().key(), "NOTICE", "ANNOUNCEMENT", title, null,
                "https://example.com/notice", published.atTime(9, 0), "eastmoney",
                "NORMAL", "NEUTRAL", "hash" + Math.abs(title.hashCode()),
                LocalDateTime.of(2026, 10, 17, 9, 0));
        List<WarehouseRows.Event> events = new ArrayList<>(
                base.events().data() == null ? List.of() : base.events().data());
        events.add(event);
        return new FactsSnapshot(base.inputCode(), base.instrument(), base.identity(),
                base.quote(), base.prices(), base.valuations(), base.indexValuation(),
                base.financials(), base.moneyflow(), base.indexProfile(), base.industry(),
                new FactsSnapshot.Domain<>(List.copyOf(events), DataStatus.OK, null,
                        published.toString()),
                base.syncPending(), base.retryAfterMs(), base.fromCache(),
                base.snapshotId(), base.snapshotAt());
    }
}
