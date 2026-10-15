package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「代码查询 · 深度分析」判定引擎的单测。
 *
 * <p>这里守的是三件最容易悄悄错掉的事：
 *
 * <ol>
 *   <li><b>边界值</b>——阈值带写的是 {@code ≤20}，写成 {@code <20} 没人看得出来，
 *       但页面上的颜色就变了。所以每一带的**端点两侧**都要各钉一条。</li>
 *   <li><b>「未确认」不许静默变成「没问题」</b>——{@code INSUFFICIENT} 与 {@code NOT_APPLICABLE}
 *       既不进净分也不进参判维度数，而且必须出现在「未计入」清单里。少了这条，
 *       「指数没有财报」会被读成「指数这一块没毛病」。</li>
 *   <li><b>硬否决不许被「缺值」触发</b>——{@code ScreeningRules.vetoes} 把「字段缺失」也记成命中，
 *       而本页的硬否决只在**有值且越线**时才算数。混起来，一只腾讯兜底拿到行情的正常个股
 *       会因为「PB 缺失」被判「风险偏高」。</li>
 * </ol>
 */
class AnalysisRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 一只「各项都合格」的个股。每个用例只改要测的那一项。 */
    private static CodeLookupDTO stock(Fund f) {
        return new CodeLookupDTO("300274", "0.300274", "阳光电源",
                CodeLookupDTO.Kind.STOCK, "深市股票",
                quote(new BigDecimal("85.00"), new BigDecimal("18"), new BigDecimal("2.2"),
                        "电源设备", new BigDecimal("180000000000")),
                f.build(),
                new BigDecimal("85.00"), "2026-10-14", List.of(),
                position(new BigDecimal("35"), new BigDecimal("2.5"), 400, "2026-10-14"),
                valuation(new BigDecimal("18"), new BigDecimal("25"), 2400, "2016-10-14"),
                List.of(), "2026-10-15T10:00:00", false);
    }

    private static CodeLookupDTO stock() {
        return stock(new Fund());
    }

    /** 沪深300ETF：行情行是 ETF 自己的，估值那一段是**所跟踪指数**的。 */
    private static CodeLookupDTO etf() {
        return new CodeLookupDTO("510300", "SH000300", "沪深300",
                CodeLookupDTO.Kind.INDEX_FUND, "池内指数（输入的是它的代表 ETF）",
                quote(new BigDecimal("4.05"), null, null, null, new BigDecimal("40000000000")),
                new Fund().amount(new BigDecimal("900000000")).turnover(new BigDecimal("2.4")).build(),
                new BigDecimal("4.05"), "2026-10-14", List.of(),
                position(new BigDecimal("52"), new BigDecimal("-1.2"), 400, "2026-10-14"),
                valuation(new BigDecimal("13.55"), new BigDecimal("42.10"), 3787, "2011-06-28"),
                List.of(), "2026-10-15T10:00:00", false);
    }

    /** 池外指数：**不取行情**，所以连成交额与换手率都没有。 */
    private static CodeLookupDTO offPoolIndex() {
        return new CodeLookupDTO("930740", "930740", "中证沪港深人工智能",
                CodeLookupDTO.Kind.OFF_POOL_INDEX, "池外指数（中证官网）",
                null, CodeLookupDTO.FundamentalsView.noQuote(),
                null, null, List.of(),
                position(new BigDecimal("78"), new BigDecimal("6.0"), 400, "2026-10-14"),
                valuation(new BigDecimal("58.2"), new BigDecimal("88.0"), 900, "2020-01-02"),
                List.of(), "2026-10-15T10:00:00", false);
    }

    private static CodeLookupDTO.QuoteView quote(BigDecimal price, BigDecimal pe, BigDecimal pb,
                                                 String industry, BigDecimal cap) {
        return new CodeLookupDTO.QuoteView("eastmoney", "东财", "某标的", price,
                new BigDecimal("1.20"), pe, pb, industry, cap);
    }

    private static CodeLookupDTO.PositionView position(BigDecimal pct, BigDecimal vsMa250,
                                                       int bars, String lastTradeDate) {
        return new CodeLookupDTO.PositionView(true, pct, new BigDecimal("-10.0"), vsMa250, bars,
                lastTradeDate, List.of());
    }

    private static CodeLookupDTO.ValuationView valuation(BigDecimal pe, BigDecimal pct, Integer len,
                                                         String from) {
        return new CodeLookupDTO.ValuationView(true, "csindex", "中证官网（中证口径）",
                "CSI_PE_TTM_ROLLING_10Y", pe, pct, "2026-10-14", len, from, "2026-10-14",
                List.of(), List.of());
    }

    private static CodeLookupDTO withValuation(CodeLookupDTO dto, CodeLookupDTO.ValuationView v) {
        return new CodeLookupDTO(dto.code(), dto.resolvedCode(), dto.name(), dto.kind(),
                dto.kindLabel(), dto.quote(), dto.fundamentals(), dto.currentPrice(),
                dto.currentPriceDate(), dto.priceLookbacks(), dto.position(), v, dto.notes(),
                dto.snapshotAt(), dto.fromCache());
    }

    private static CodeLookupDTO withPosition(CodeLookupDTO dto, CodeLookupDTO.PositionView p) {
        return new CodeLookupDTO(dto.code(), dto.resolvedCode(), dto.name(), dto.kind(),
                dto.kindLabel(), dto.quote(), dto.fundamentals(), dto.currentPrice(),
                dto.currentPriceDate(), dto.priceLookbacks(), p, dto.valuation(), dto.notes(),
                dto.snapshotAt(), dto.fromCache());
    }

    /**
     * 腾讯兜底拿到的那一份：**行情只给价格与成交额里的价格**——PE / PB / 市值 / 行业全为 null，
     * 基本面那一块整体 {@code available=false}。这一份是「缺值」这一路的主要样本。
     */
    private static CodeLookupDTO fallbackStock() {
        CodeLookupDTO base = stock();
        return new CodeLookupDTO(base.code(), base.resolvedCode(), base.name(), base.kind(),
                base.kindLabel(),
                new CodeLookupDTO.QuoteView("tencent", "腾讯", "阳光电源",
                        new BigDecimal("85.00"), new BigDecimal("1.20"), null, null, null, null),
                CodeLookupDTO.FundamentalsView.unavailableFrom("tencent", "腾讯"),
                base.currentPrice(), base.currentPriceDate(), List.of(), base.position(),
                base.valuation(), List.of("行情取自腾讯，该源只给价格与成交额"),
                base.snapshotAt(), false);
    }

    /**
     * 基本面构造器。这一块 17 个字段且**大多数用例只改一个**，
     * 用构造器参数会写成一片 {@code null, null, null}，改错位置看不出来。
     */
    private static final class Fund {
        private BigDecimal roe = new BigDecimal("18");
        private BigDecimal revenue = new BigDecimal("25");
        private BigDecimal profit = new BigDecimal("30");
        private BigDecimal gross = new BigDecimal("32");
        private BigDecimal debt = new BigDecimal("45");
        private BigDecimal dividend = new BigDecimal("1.5");
        private BigDecimal turnover = new BigDecimal("3.0");
        private BigDecimal amount = new BigDecimal("1200000000");
        private BigDecimal floatCap = new BigDecimal("150000000000");
        private BigDecimal change60d = new BigDecimal("5.0");
        private String listDate = "2011-11-02";

        Fund roe(BigDecimal v) { this.roe = v; return this; }
        Fund revenue(BigDecimal v) { this.revenue = v; return this; }
        Fund profit(BigDecimal v) { this.profit = v; return this; }
        Fund gross(BigDecimal v) { this.gross = v; return this; }
        Fund debt(BigDecimal v) { this.debt = v; return this; }
        Fund dividend(BigDecimal v) { this.dividend = v; return this; }
        Fund turnover(BigDecimal v) { this.turnover = v; return this; }
        Fund amount(BigDecimal v) { this.amount = v; return this; }
        Fund change60d(BigDecimal v) { this.change60d = v; return this; }

        CodeLookupDTO.FundamentalsView build() {
            boolean any = roe != null || revenue != null || profit != null || gross != null
                    || debt != null || dividend != null || turnover != null || amount != null;
            return new CodeLookupDTO.FundamentalsView(any, "eastmoney", "东财",
                    roe, revenue, profit, gross, debt, dividend, turnover, amount, floatCap,
                    change60d, new BigDecimal("20"), new BigDecimal("30"), listDate,
                    any ? List.of() : List.of("东财这一行没有返回这组字段"));
        }
    }

    // ------------------------------------------------------------------
    // 取值助手
    // ------------------------------------------------------------------

    private static AnalysisRules.Analysis analyse(CodeLookupDTO dto) {
        return AnalysisRules.analyse(dto, TODAY);
    }

    private static AnalysisRules.DimensionCard card(AnalysisRules.Analysis a, String key) {
        return a.dimensions().stream().filter(d -> d.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("没有这个维度卡：" + key));
    }

    private static AnalysisRules.Metric metric(AnalysisRules.DimensionCard c, String label) {
        return c.metrics().stream().filter(m -> m.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("维度 " + c.key() + " 里没有指标：" + label));
    }

    private static AnalysisRules.CheckupItem item(AnalysisRules.Checkup c, String rule) {
        return c.items().stream().filter(i -> i.rule().equals(rule)).findFirst()
                .orElseThrow(() -> new AssertionError("体检里没有 " + rule));
    }

    // ==================================================================
    // 一、阈值带的边界（端点两侧各一条）
    // ==================================================================

    @Test
    void peOfExactlyTwentyIsStillFavorableButATinyBitMoreIsNot() {
        // 「≤20」写成「<20」是这类代码最常见的静默错误：页面颜色变了，没有任何测试会红。
        AnalysisRules.DimensionCard at = card(analyse(stock(new Fund())), "valuation");
        assertThat(metric(at, "PE(TTM)").stance()).isEqualTo(AnalysisRules.Stance.FAVORABLE);

        CodeLookupDTO over = withValuation(stock(), valuation(new BigDecimal("20.01"),
                new BigDecimal("25"), 2400, "2016-10-14"));
        assertThat(metric(card(analyse(over), "valuation"), "PE(TTM)").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
    }

    @Test
    void pePercentileBandsAreClosedOnTheLeftAndOpenOnTheRight() {
        CodeLookupDTO at30 = withValuation(stock(), valuation(new BigDecimal("18"),
                new BigDecimal("30"), 2400, "2016-10-14"));
        assertThat(metric(card(analyse(at30), "valuation"), "PE 历史分位").stance())
                .isEqualTo(AnalysisRules.Stance.FAVORABLE);

        CodeLookupDTO at30dot1 = withValuation(stock(), valuation(new BigDecimal("18"),
                new BigDecimal("30.01"), 2400, "2016-10-14"));
        assertThat(metric(card(analyse(at30dot1), "valuation"), "PE 历史分位").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);

        CodeLookupDTO at70 = withValuation(stock(), valuation(new BigDecimal("18"),
                new BigDecimal("70"), 2400, "2016-10-14"));
        assertThat(metric(card(analyse(at70), "valuation"), "PE 历史分位").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);

        CodeLookupDTO at70dot1 = withValuation(stock(), valuation(new BigDecimal("18"),
                new BigDecimal("70.01"), 2400, "2016-10-14"));
        assertThat(metric(card(analyse(at70dot1), "valuation"), "PE 历史分位").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
    }

    @Test
    void roeAndDebtAndGrossMarginSwitchOnTheirOwnEndpoints() {
        // ROE 12 / 6
        assertThat(metric(quality(stock(new Fund().roe(new BigDecimal("12")))), "ROE(加权)").stance())
                .isEqualTo(AnalysisRules.Stance.FAVORABLE);
        assertThat(metric(quality(stock(new Fund().roe(new BigDecimal("11.99")))), "ROE(加权)").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(quality(stock(new Fund().roe(new BigDecimal("6")))), "ROE(加权)").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(quality(stock(new Fund().roe(new BigDecimal("5.99")))), "ROE(加权)").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);

        // 毛利率 30 / 15
        assertThat(metric(quality(stock(new Fund().gross(new BigDecimal("30")))), "毛利率").stance())
                .isEqualTo(AnalysisRules.Stance.FAVORABLE);
        assertThat(metric(quality(stock(new Fund().gross(new BigDecimal("15")))), "毛利率").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(quality(stock(new Fund().gross(new BigDecimal("14.99")))), "毛利率").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);

        // 资产负债率 40 / 60（**上界型**，与 ROE 方向相反）
        assertThat(metric(health(stock(new Fund().debt(new BigDecimal("40")))), "资产负债率").stance())
                .isEqualTo(AnalysisRules.Stance.FAVORABLE);
        assertThat(metric(health(stock(new Fund().debt(new BigDecimal("40.01")))), "资产负债率").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(health(stock(new Fund().debt(new BigDecimal("60")))), "资产负债率").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(health(stock(new Fund().debt(new BigDecimal("60.01")))), "资产负债率").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
    }

    @Test
    void turnoverFillsTheTwoGapsTheCharterLeftOpenWithNeutral() {
        // 章程 §9.5 那一行只写了「1–5% 正面 / 0.5–1% 或 5–10% 中性 / <0.2% 或 >15% 负面」，
        // 0.2–0.5 与 10–15 两处留空。补成中性（不是补成正面，也不是补成负面）。
        assertThat(metric(liquidity(stock(new Fund().turnover(new BigDecimal("0.3")))), "换手率").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(liquidity(stock(new Fund().turnover(new BigDecimal("12")))), "换手率").stance())
                .isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(liquidity(stock(new Fund().turnover(new BigDecimal("1")))), "换手率").stance())
                .isEqualTo(AnalysisRules.Stance.FAVORABLE);
        assertThat(metric(liquidity(stock(new Fund().turnover(new BigDecimal("0.19")))), "换手率").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
        assertThat(metric(liquidity(stock(new Fund().turnover(new BigDecimal("15.01")))), "换手率").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
    }

    @Test
    void aFallingSixtyDayChangeIsNegativeNeverCheap() {
        // 章程 §1：「不因为最近跌得多而推荐」。跌 30% 必须是偏负面，不许被读成「便宜」。
        AnalysisRules.Metric m = metric(
                card(analyse(stock(new Fund().change60d(new BigDecimal("-30")))), "momentum"),
                "60 日涨跌");
        assertThat(m.stance()).isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
        assertThat(m.basis()).contains("不是「便宜」的理由");
    }

    @Test
    void dividendYieldBandsSwitchOnThreeAndOne() {
        assertThat(metric(card(analyse(stock(new Fund().dividend(new BigDecimal("3")))), "valuation"),
                "股息率").stance()).isEqualTo(AnalysisRules.Stance.FAVORABLE);
        assertThat(metric(card(analyse(stock(new Fund().dividend(new BigDecimal("2.99")))), "valuation"),
                "股息率").stance()).isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(card(analyse(stock(new Fund().dividend(new BigDecimal("1")))), "valuation"),
                "股息率").stance()).isEqualTo(AnalysisRules.Stance.NEUTRAL);
        assertThat(metric(card(analyse(stock(new Fund().dividend(new BigDecimal("0.99")))), "valuation"),
                "股息率").stance()).isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
    }

    @Test
    void noDailyBarsMeansTheTechnicalDimensionIsUnconfirmedNotFine() {
        // 日线取不到 → 技术面整维「未确认」，且必须出现在「未计入」里。
        // 静默当成中性的话，一只连 K 线都没取到的票会看起来「各维正常」。
        CodeLookupDTO dto = withPosition(stock(),
                new CodeLookupDTO.PositionView(false, null, null, null, 0, null,
                        List.of("日线不足 250 根，价格位置与均线均未确认")));

        AnalysisRules.Analysis a = analyse(dto);
        AnalysisRules.DimensionCard tech = card(a, "technical");
        assertThat(tech.stance()).isEqualTo(AnalysisRules.Stance.INSUFFICIENT);
        assertThat(tech.reasons()).isNotEmpty();
        assertThat(a.tendency().excluded()).anyMatch(s -> s.contains("技术面"));
    }

    private static AnalysisRules.DimensionCard quality(CodeLookupDTO dto) {
        return card(analyse(dto), "quality");
    }

    private static AnalysisRules.DimensionCard health(CodeLookupDTO dto) {
        return card(analyse(dto), "health");
    }

    private static AnalysisRules.DimensionCard liquidity(CodeLookupDTO dto) {
        return card(analyse(dto), "liquidity");
    }

    // ==================================================================
    // 二、四态与不变式
    // ==================================================================

    @Test
    void onlyTheFavorableAndNeutralDimensionsAreAllowedToSayNothing() {
        // 不变式：偏正面 / 中性 ⟺ reasons 为空。
        // 另外三态都必须给话：偏负面要给踩了哪条线、未确认要给为什么没取到、
        // 不适用要给「为什么这一维不存在」。少了后者，一个维度凭空消失是看不出来的。
        List<AnalysisRules.Analysis> samples = List.of(
                analyse(stock()),
                analyse(stock(new Fund().roe(null).gross(null))),
                analyse(stock(new Fund().debt(new BigDecimal("90")).roe(new BigDecimal("2")))),
                analyse(etf()),
                analyse(offPoolIndex()));

        for (AnalysisRules.Analysis a : samples) {
            for (AnalysisRules.DimensionCard d : a.dimensions()) {
                boolean silent = d.stance() == AnalysisRules.Stance.FAVORABLE
                        || d.stance() == AnalysisRules.Stance.NEUTRAL;
                assertThat(d.reasons().isEmpty())
                        .as("%s 的 reasons（%s）", d.key(), d.stance())
                        .isEqualTo(silent);
            }
        }
    }

    @Test
    void oneBadMetricDragsItsWholeDimensionDownInsteadOfBeingOutvoted() {
        // 一只亏损公司：PE 偏负面、分位偏正面、股息中性。多数决会把它判成中性——
        // 一个亏损的公司在估值卡上写着「中性」。负面一票否决就是为了这条路。
        CodeLookupDTO dto = withValuation(stock(),
                valuation(new BigDecimal("-5"), new BigDecimal("18"), 2400, "2016-10-14"));

        AnalysisRules.DimensionCard v = card(analyse(dto), "valuation");
        assertThat(metric(v, "PE(TTM)").stance()).isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
        assertThat(metric(v, "PE 历史分位").stance()).isEqualTo(AnalysisRules.Stance.FAVORABLE);
        assertThat(v.stance()).isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
    }

    @Test
    void insufficientAndNotApplicableNeitherAddNorSubtract() {
        // 净资产收益率与毛利率都取不到 → 盈利质量整维未确认。
        CodeLookupDTO dto = stock(new Fund().roe(null).gross(null));
        AnalysisRules.Analysis a = analyse(dto);

        AnalysisRules.DimensionCard q = card(a, "quality");
        assertThat(q.stance()).isEqualTo(AnalysisRules.Stance.INSUFFICIENT);
        assertThat(q.counts()).isFalse();
        assertThat(q.score()).isZero();

        // 参判维度从 7 掉到 6，净分不减不增（不是「没问题」，是「没算」）。
        assertThat(a.tendency().countedDimensions()).isEqualTo(6);
        assertThat(a.tendency().netScore()).isEqualTo(5);
        assertThat(a.tendency().excluded())
                .anyMatch(s -> s.contains("盈利质量") && s.contains("未计入"));
    }

    @Test
    void theWordNotApplicableIsReservedForCategoriesThatHaveNoSuchDimension() {
        // ETF 没有财报 → NOT_APPLICABLE；个股取不到 → INSUFFICIENT。两件事不许说成同一件。
        AnalysisRules.Analysis etf = analyse(etf());
        assertThat(card(etf, "growth").stance()).isEqualTo(AnalysisRules.Stance.NOT_APPLICABLE);
        assertThat(card(etf, "growth").metrics()).isEmpty();
        assertThat(etf.tendency().excluded()).anyMatch(s -> s.contains("成长") && s.contains("不适用"));

        AnalysisRules.Analysis stock = analyse(stock(new Fund().roe(null).gross(null)));
        assertThat(card(stock, "quality").stance()).isEqualTo(AnalysisRules.Stance.INSUFFICIENT);
        assertThat(card(stock, "quality").metrics()).isNotEmpty();
    }

    @Test
    void aFinancialStockHasNoFinancialHealthDimensionAtAll() {
        // V11 在本节是「该维度不判定」而不是「剔除」（章程 §9.3）。
        CodeLookupDTO bank = new CodeLookupDTO("600036", "1.600036", "招商银行",
                CodeLookupDTO.Kind.STOCK, "沪市股票",
                quote(new BigDecimal("40"), new BigDecimal("6"), new BigDecimal("0.9"),
                        "银行", new BigDecimal("900000000000")),
                new Fund().build(),
                new BigDecimal("40"), "2026-10-14", List.of(),
                position(new BigDecimal("50"), new BigDecimal("3"), 400, "2026-10-14"),
                valuation(new BigDecimal("6"), new BigDecimal("40"), 2400, "2016-10-14"),
                List.of(), "2026-10-15T10:00:00", false);

        AnalysisRules.Analysis a = analyse(bank);
        assertThat(card(a, "health").stance()).isEqualTo(AnalysisRules.Stance.NOT_APPLICABLE);
        assertThat(card(a, "health").reasons()).isNotEmpty();
        // 而且它在体检里只作为「口径不可比」出现，**不是硬否决**。
        assertThat(item(a.checkup(), "V11").hit()).isTrue();
        assertThat(a.checkup().hardVetoes()).isEmpty();
    }

    // ==================================================================
    // 三、硬否决
    // ==================================================================

    @Test
    void anStNameIsAHardVetoWhateverTheOtherDimensionsSay() {
        CodeLookupDTO dto = new CodeLookupDTO("600001", "1.600001", "ST某某",
                CodeLookupDTO.Kind.STOCK, "沪市股票", stock().quote(), stock().fundamentals(),
                new BigDecimal("85"), "2026-10-14", List.of(), stock().position(),
                stock().valuation(), List.of(), "2026-10-15T10:00:00", false);

        AnalysisRules.Analysis a = analyse(dto);
        assertThat(a.tendency().tendency()).isEqualTo(AnalysisRules.Tendency.HIGH_RISK);
        assertThat(a.checkup().hardVetoes()).anyMatch(s -> s.contains("V1"));
        // 十个「偏正面」不许抵掉一条 ST。净分照算，但结论不看它。
        assertThat(a.tendency().netScore()).isPositive();
    }

    @Test
    void aNegativePeIsAHardVetoNotACheapStock() {
        CodeLookupDTO dto = new CodeLookupDTO("300999", "0.300999", "某亏损公司",
                CodeLookupDTO.Kind.STOCK, "深市股票",
                quote(new BigDecimal("12.00"), new BigDecimal("-8"), new BigDecimal("3.0"),
                        "电源设备", new BigDecimal("30000000000")),
                new Fund().build(),
                new BigDecimal("12.00"), "2026-10-14", List.of(),
                position(new BigDecimal("20"), new BigDecimal("-3"), 400, "2026-10-14"),
                valuation(new BigDecimal("-8"), new BigDecimal("20"), 2400, "2016-10-14"),
                List.of(), "2026-10-15T10:00:00", false);

        AnalysisRules.Analysis a = analyse(dto);
        assertThat(a.tendency().tendency()).isEqualTo(AnalysisRules.Tendency.HIGH_RISK);
        assertThat(a.checkup().hardVetoes()).anyMatch(s -> s.contains("V5"));
        // 负 PE 在估值卡里也不能是「便宜」。
        assertThat(metric(card(a, "valuation"), "PE(TTM)").stance())
                .isEqualTo(AnalysisRules.Stance.UNFAVORABLE);
        assertThat(card(a, "valuation").reasons()).anyMatch(s -> s.contains("亏损"));
    }

    @Test
    void aMissingFieldIsNotAHardVeto() {
        // 腾讯兜底只给价格与成交额，PB / PE / 负债率 / 市值全是 null。
        // vetoes() 会把它们都记成「命中」，但**缺值不是踩线**——否则正常个股会被判「风险偏高」。
        CodeLookupDTO dto = fallbackStock();

        AnalysisRules.Analysis a = analyse(dto);
        assertThat(item(a.checkup(), "V6").hit()).isTrue();     // 体检里照实说是「缺失」
        assertThat(item(a.checkup(), "V6").detail()).contains("缺失");
        assertThat(item(a.checkup(), "V3").hit()).isTrue();
        assertThat(a.checkup().hardVetoes()).isEmpty();          // 但硬否决一条都不成立
        assertThat(a.tendency().tendency()).isNotEqualTo(AnalysisRules.Tendency.HIGH_RISK);
    }

    @Test
    void aSubNewStockIsShownInTheCheckupButIsNotAHardVeto() {
        // 章程 §9.6 列的是「ST / 退市 / 负 PE / 停牌」——次新不在其中。
        CodeLookupDTO dto = new CodeLookupDTO("301999", "0.301999", "N某某",
                CodeLookupDTO.Kind.STOCK, "深市股票", stock().quote(), stock().fundamentals(),
                stock().currentPrice(), stock().currentPriceDate(), List.of(),
                stock().position(), stock().valuation(), List.of(), "2026-10-15T10:00:00", false);

        AnalysisRules.Analysis a = analyse(dto);
        assertThat(item(a.checkup(), "V1").hit()).isTrue();
        assertThat(a.checkup().hardVetoes()).isEmpty();
    }

    @Test
    void aSuspendedStockIsAHardVeto() {
        CodeLookupDTO dto = new CodeLookupDTO("600002", "1.600002", "某某股份",
                CodeLookupDTO.Kind.STOCK, "沪市股票",
                quote(BigDecimal.ZERO, new BigDecimal("18"), new BigDecimal("2.2"),
                        "电源设备", new BigDecimal("180000000000")),
                new Fund().build(),
                BigDecimal.ZERO, "2026-10-14", List.of(), stock().position(), stock().valuation(),
                List.of(), "2026-10-15T10:00:00", false);

        AnalysisRules.Analysis a = analyse(dto);
        assertThat(a.tendency().tendency()).isEqualTo(AnalysisRules.Tendency.HIGH_RISK);
        assertThat(a.checkup().hardVetoes()).anyMatch(s -> s.contains("V10"));
    }

    // ==================================================================
    // 四、倾向合成
    // ==================================================================

    @Test
    void aHealthyStockWithMostlyFavorableDimensionsIsWatchNotBuy() {
        AnalysisRules.TendencyVerdict t = analyse(stock()).tendency();
        assertThat(t.tendency()).isEqualTo(AnalysisRules.Tendency.WATCH);
        assertThat(t.countedDimensions()).isEqualTo(7);
        assertThat(t.netScore()).isEqualTo(6);
        assertThat(t.reasons()).anyMatch(s -> s.contains("净分 6"));
    }

    @Test
    void negativeDimensionsNetOutToHighRisk() {
        // 负债率高、毛利率低、ROE 低、60 日跌 25%、PE 分位 85% —— 四个维度翻负。
        CodeLookupDTO dto = withValuation(stock(new Fund()
                        .roe(new BigDecimal("3")).gross(new BigDecimal("8"))
                        .debt(new BigDecimal("75")).revenue(new BigDecimal("-12"))
                        .profit(new BigDecimal("-30"))
                        .change60d(new BigDecimal("-25"))),
                valuation(new BigDecimal("52"), new BigDecimal("85"), 2400, "2016-10-14"));

        AnalysisRules.TendencyVerdict t = analyse(dto).tendency();
        assertThat(t.tendency()).isEqualTo(AnalysisRules.Tendency.HIGH_RISK);
        assertThat(t.netScore()).isLessThanOrEqualTo(-2);
    }

    @Test
    void tooFewJudgedDimensionsMeansNoTendencyAtAll() {
        // 腾讯兜底：只有估值（来自估值源）与技术面两维拿得到，参判数 2 < 3。
        AnalysisRules.TendencyVerdict t = analyse(fallbackStock()).tendency();
        assertThat(t.tendency()).isEqualTo(AnalysisRules.Tendency.INSUFFICIENT);
        assertThat(t.countedDimensions()).isLessThan(AnalysisRules.MIN_COUNTED_DIMENSIONS);
        assertThat(t.reasons()).anyMatch(s -> s.contains("不给倾向"));
        assertThat(t.excluded()).isNotEmpty();
    }

    @Test
    void anOffPoolIndexNeverGetsATendencyBecauseItOnlyHasTwoDimensions() {
        // 池外指数不取行情：只有估值与技术面。按 §9.6 的 D<3，它恒为「数据不足」——
        // 这条不是 bug，是章程算出来的结果，钉在这里免得日后被当成 bug 改掉。
        AnalysisRules.TendencyVerdict t = analyse(offPoolIndex()).tendency();
        assertThat(t.tendency()).isEqualTo(AnalysisRules.Tendency.INSUFFICIENT);
        assertThat(t.countedDimensions()).isEqualTo(2);
    }

    @Test
    void anEtfGetsATendencyFromThreeDimensions() {
        // 估值（跟踪指数口径）+ 规模与流动性（换手率）+ 技术面。成长/盈利质量/财务健康不适用。
        // 三张卡：估值偏正面（PE 13.55 偏正面 + 分位 42% 中性）、流动性偏正面（换手 2.4%）、
        // 技术面中性（价格分位 52% + 对 MA250 −1.2% 都落在中性带）→ 净分 +2 → 偏可关注。
        AnalysisRules.Analysis a = analyse(etf());
        assertThat(a.tendency().countedDimensions()).isEqualTo(3);
        assertThat(a.tendency().netScore()).isEqualTo(2);
        assertThat(a.tendency().tendency()).isEqualTo(AnalysisRules.Tendency.WATCH);
        assertThat(metric(card(a, "valuation"), "PE(TTM)").display()).isEqualTo("13.55");
        assertThat(metric(card(a, "valuation"), "PE 历史分位").display()).isEqualTo("42.1%");
    }

    /**
     * 章程 §1 的禁止词表不因本节而放宽（§9.1）。这条把整份结论的每一行都过一遍——
     * 一个「建议买入」从规则引擎里漏出来，是这个功能最坏的失败方式。
     */
    @Test
    void noConclusionEverUsesTheForbiddenVocabulary() {
        java.util.regex.Pattern forbidden = java.util.regex.Pattern.compile(
                "买入|卖出|加仓|减仓|建议持仓|目标价|必涨|适合入手|可以建仓");

        for (CodeLookupDTO dto : List.of(stock(), etf(), offPoolIndex())) {
            AnalysisRules.Analysis a = analyse(dto);
            assertThat(a.tendency().reasons()).as("倾向理由").allMatch(s -> !forbidden.matcher(s).find());
            assertThat(a.tendency().excluded()).as("未计入清单").allMatch(s -> !forbidden.matcher(s).find());
            assertThat(a.pendingChecks()).as("人工核实清单").allMatch(s -> !forbidden.matcher(s).find());
            for (AnalysisRules.DimensionCard d : a.dimensions()) {
                assertThat(d.reasons()).as("%s 的理由", d.key())
                        .allMatch(s -> !forbidden.matcher(s).find());
                for (AnalysisRules.Metric m : d.metrics()) {
                    assertThat(m.basis()).as("%s 的依据", m.label())
                            .doesNotMatch(".*" + forbidden.pattern() + ".*");
                }
            }
            assertThat(a.tendency().tendency().label())
                    .isIn("偏可关注", "需等待", "风险偏高", "数据不足（本次不给倾向）");
        }
    }

    // ==================================================================
    // 五、分位历史不够长就不许称作分位
    // ==================================================================

    @Test
    void aPercentileFromLessThanThreeYearsOfHistoryIsNotConfirmed() {
        CodeLookupDTO dto = withValuation(stock(),
                valuation(new BigDecimal("18"), new BigDecimal("25"), 500, "2024-01-02"));

        AnalysisRules.Metric m = metric(card(analyse(dto), "valuation"), "PE 历史分位");
        assertThat(m.stance()).isEqualTo(AnalysisRules.Stance.INSUFFICIENT);
        assertThat(m.display()).isNull();
        assertThat(m.basis()).contains("不足 3 年");
    }

    @Test
    void theHistorySpanIsMeasuredInYearsNotInObservationCounts() {
        // 蛋卷是**周频**：3 年只有 156 个观测，而日频 3 年有 750 个。
        // 拿条数判「几年」会把 3 年的周频序列误判成「不到 1 年」。
        CodeLookupDTO dto = withValuation(stock(),
                valuation(new BigDecimal("18"), new BigDecimal("25"), 156, "2023-10-14"));

        assertThat(metric(card(analyse(dto), "valuation"), "PE 历史分位").stance())
                .isEqualTo(AnalysisRules.Stance.FAVORABLE);
    }

    // ==================================================================
    // 六、体检
    // ==================================================================

    @Test
    void anEtfIsNotChargedWithTheFinancialRulesItCannotHave() {
        // V5–V9、V11、V12 读的是财报字段，ETF 的行情行里根本没有。
        // 全跑一遍的结果是每只 ETF 稳定拿到四条「字段缺失」，那不是体检结果。
        AnalysisRules.Checkup c = analyse(etf()).checkup();
        assertThat(c.applicable()).isTrue();
        assertThat(c.items()).extracting(AnalysisRules.CheckupItem::rule)
                .containsExactly("V1", "V2", "V3", "V4", "V10");
        assertThat(c.items()).allMatch(i -> !i.hit());
        assertThat(c.note()).contains("稳健档").contains("不适用");
    }

    @Test
    void anIndexHasNoCheckupAtAll() {
        AnalysisRules.Checkup c = analyse(offPoolIndex()).checkup();
        assertThat(c.applicable()).isFalse();
        assertThat(c.items()).isEmpty();
        assertThat(c.note()).contains("不适用");
    }

    @Test
    void aStockRunsTheFullTwelveRuleCheckupInOrder() {
        AnalysisRules.Checkup c = analyse(stock()).checkup();
        assertThat(c.items()).extracting(AnalysisRules.CheckupItem::rule)
                .containsExactly("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8",
                        "V9", "V10", "V11", "V12");
        assertThat(c.items()).allMatch(i -> !i.hit());
        assertThat(c.hardVetoes()).isEmpty();
    }

    @Test
    void aHitCarriesTheActualValueAndTheThresholdNotJustAFlag() {
        // 「命中」两个字对用户毫无用处，必须带上实际值与阈值。
        CodeLookupDTO dto = withValuation(stock(), valuation(new BigDecimal("52"),
                new BigDecimal("25"), 2400, "2016-10-14"));
        CodeLookupDTO expensive = new CodeLookupDTO(dto.code(), dto.resolvedCode(), dto.name(),
                dto.kind(), dto.kindLabel(),
                quote(new BigDecimal("85"), new BigDecimal("52"), new BigDecimal("2.2"),
                        "电源设备", new BigDecimal("180000000000")),
                dto.fundamentals(), dto.currentPrice(), dto.currentPriceDate(), List.of(),
                dto.position(), dto.valuation(), List.of(), dto.snapshotAt(), false);

        AnalysisRules.CheckupItem v12 = item(analyse(expensive).checkup(), "V12");
        assertThat(v12.hit()).isTrue();
        assertThat(v12.detail()).contains("52").contains("30");
    }

    // ==================================================================
    // 七、DTO 还原成 StockRow
    // ==================================================================

    @Test
    void theRebuiltRowCarriesEveryFieldTheVetoesRead() {
        // rowOf() 是这份实现里唯一一处「把 DTO 还原成别的类型」的地方。
        // 只要 StockRow 加了 vetoes() 会读的新字段而这里没同步，体检就会静默地拿 null 去判——
        // 所以这一条把**逐字段**对上，而不是只测一两个。
        CodeLookupDTO dto = stock();
        StockRow row = AnalysisRules.rowOf(dto);

        assertThat(row.getCode()).isEqualTo("0.300274");
        assertThat(row.getName()).isEqualTo("阳光电源");
        assertThat(row.getIndustry()).isEqualTo("电源设备");
        assertThat(row.getPrice()).isEqualByComparingTo("85.00");
        assertThat(row.getTotalMarketCap()).isEqualByComparingTo("180000000000");
        assertThat(row.getPb()).isEqualByComparingTo("2.2");
        assertThat(row.getPeTtm()).isEqualByComparingTo("18");
        assertThat(row.getAmount()).isEqualByComparingTo("1200000000");
        assertThat(row.getTurnoverRate()).isEqualByComparingTo("3.0");
        assertThat(row.getFloatMarketCap()).isEqualByComparingTo("150000000000");
        assertThat(row.getRoe()).isEqualByComparingTo("18");
        assertThat(row.getRevenueGrowth()).isEqualByComparingTo("25");
        assertThat(row.getProfitGrowth()).isEqualByComparingTo("30");
        assertThat(row.getGrossMargin()).isEqualByComparingTo("32");
        assertThat(row.getDebtRatio()).isEqualByComparingTo("45");
        assertThat(row.getDividendYield()).isEqualByComparingTo("1.5");
        assertThat(row.getBps()).isEqualByComparingTo("30");
        assertThat(row.getChange60d()).isEqualByComparingTo("5.0");
        assertThat(row.getYtdChange()).isEqualByComparingTo("20");
        assertThat(row.getListDate()).isEqualTo(LocalDate.of(2011, 11, 2));
        assertThat(row.effectiveMarketCap()).isEqualByComparingTo("180000000000");
        assertThat(row.isFinancialOrRealEstate()).isFalse();
        assertThat(row.listedYears(TODAY)).isEqualTo(14);
    }

    @Test
    void aQuoteThatWasNeverFetchedRebuildsIntoAnEmptyRowNotAFakeOne() {
        StockRow row = AnalysisRules.rowOf(offPoolIndex());
        assertThat(row.getPrice()).isNull();
        assertThat(row.getPeTtm()).isNull();
        assertThat(row.getRoe()).isNull();
        assertThat(row.getIndustry()).isNull();
        // 行业为 null 时 isFinancialOrRealEstate() 为 false——这**不是**「确认不是金融地产」，
        // 所以产业维度那一块要靠 isIndustryUnknown() 自己说清楚。
        assertThat(row.isIndustryUnknown()).isTrue();
    }

    // ==================================================================
    // 八、「需要你人工核实的」全是问句
    // ==================================================================

    @Test
    void everyPendingCheckIsAQuestionAndNoneAnswersItself() {
        // 章程 §9.12：「未来往哪发展」只能以提问式出现。提问，不回答。
        for (CodeLookupDTO dto : List.of(stock(), etf(), offPoolIndex())) {
            List<String> checks = analyse(dto).pendingChecks();
            assertThat(checks).isNotEmpty();
            assertThat(checks).allMatch(s -> s.startsWith("需核实："));
            assertThat(checks).allMatch(s -> s.endsWith("？"));
        }
    }

    @Test
    void theCaliberWarningAndDisclaimerAreServedWithTheResult() {
        // 绝对口径那条声明不写在章程里就算没写：用户看不到就会拿两页数字互相印证。
        assertThat(CodeLookupAnalysisDTO.CALIBER_ABSOLUTE).contains("绝对口径").contains("不可互相印证");
        assertThat(CodeLookupAnalysisDTO.DISCLAIMER).contains("不构成投资建议");
    }
}
