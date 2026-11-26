package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.screener.v2.TechnicalCalculator;
import com.ai.daily.screener.v2.ValuationMath;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 六维评分。**纯函数**：同一份 {@link FactsSnapshot} 与同一个 {@code today}
 * 必须得到完全相同的输出，不查库、不走网络、不读系统时钟。
 *
 * <p>缺失项的处理是全类的核心：
 * <ul>
 *   <li>子指标缺数据 → 分数 {@code null}，权重不参与归一，只降低维度覆盖率；</li>
 *   <li>整维不适用（银行没有「资产负债率」的意义）→ 分数 {@code null}、覆盖率 0；</li>
 *   <li>缺失维度的权重在总分区间里按下界 0、上界 100 计入，页面据此展示区间；</li>
 *   <li>**任何缺失都不会被当作 0 分**。0 是一个结论，缺数据不是。</li>
 * </ul>
 */
public final class InvestmentScoreModel {

    public static final String RULES_VERSION = "code-lookup-v2-p6";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal YI = BigDecimal.valueOf(100_000_000L);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private InvestmentScoreModel() {
    }

    public static ScoreResult evaluate(FactsSnapshot facts, LocalDate today) {
        LocalDate safeToday = today == null ? LocalDate.now() : today;
        Context context = new Context(facts, safeToday);

        List<DimensionScore> dimensions = switch (facts.instrument().assetType()) {
            case "ETF" -> etfDimensions(context);
            case "INDEX" -> indexDimensions(context);
            default -> stockDimensions(context);
        };

        List<String> hardFlags = hardFlags(context, dimensions);
        List<String> notes = new ArrayList<>();
        if (context.sectorProfile != null && !"GENERAL".equals(context.sectorProfile)) {
            notes.add("按 " + sectorLabel(context.sectorProfile) + " 口径评分：部分子指标不适用");
        }
        if ("CYCLICAL".equals(context.sectorProfile)) {
            notes.add("周期股：同比数据波动大，分数应结合行业周期位置阅读");
        }

        BigDecimal coverage = coverage(dimensions);
        BigDecimal raw = weightedScore(dimensions);
        if (raw == null) {
            return new ScoreResult(facts.instrument().assetType(), context.sectorProfile,
                    null, null, null, null, coverage, BigDecimal.ZERO,
                    dimensions, hardFlags, notes);
        }
        BigDecimal finalScore = BigDecimal.valueOf(50)
                .add(raw.subtract(BigDecimal.valueOf(50)).multiply(coverage, MC))
                .setScale(2, RoundingMode.HALF_UP);
        finalScore = ScoreAnchors.clamp(finalScore);

        BigDecimal scoreLow = BigDecimal.ZERO;
        BigDecimal missingWeight = BigDecimal.ZERO;
        BigDecimal availableWeight = BigDecimal.ZERO;
        for (DimensionScore dimension : dimensions) {
            if (dimension.available()) {
                scoreLow = scoreLow.add(dimension.score()
                        .multiply(BigDecimal.valueOf(dimension.weight())));
                availableWeight = availableWeight.add(BigDecimal.valueOf(dimension.weight()));
            } else {
                missingWeight = missingWeight.add(BigDecimal.valueOf(dimension.weight()));
            }
        }
        BigDecimal low = scoreLow.divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal high = low.add(missingWeight).setScale(2, RoundingMode.HALF_UP);
        BigDecimal confidence = confidence(context, coverage, dimensions);

        return new ScoreResult(facts.instrument().assetType(), context.sectorProfile,
                raw.setScale(2, RoundingMode.HALF_UP), finalScore,
                ScoreAnchors.clamp(low), ScoreAnchors.clamp(high),
                coverage, confidence, dimensions, hardFlags, notes);
    }

    // ==================================================================
    // 个股
    // ==================================================================

    private static List<DimensionScore> stockDimensions(Context ctx) {
        List<DimensionScore> dimensions = new ArrayList<>();
        dimensions.add(valuationDimension(ctx, false));
        dimensions.add(growthDimension(ctx));
        dimensions.add(qualityDimension(ctx));
        dimensions.add(healthDimension(ctx));
        dimensions.add(technicalDimension(ctx, 20));
        dimensions.add(new DimensionScore("peers", "行业同行", 8, null, BigDecimal.ZERO,
                List.of(ScoreMetric.unavailable("peer_pe", "同行 PE 分位",
                                "同行数据尚未接入快照", "ev:peer:pe"),
                        ScoreMetric.unavailable("peer_growth", "同行营收增速排名",
                                "同行数据尚未接入快照", "ev:peer:growth"),
                        ScoreMetric.unavailable("peer_roe", "同行 ROE 排名",
                                "同行数据尚未接入快照", "ev:peer:roe"),
                        ScoreMetric.unavailable("industry_momentum", "行业 20 日动量",
                                "行业板块数据尚未接入快照", "ev:peer:industry")),
                List.of("免费源暂未提供可交叉验证的同行样本，这一维不计分")));
        return dimensions;
    }

    private static DimensionScore valuationDimension(Context ctx, boolean indexLike) {
        List<ScoreMetric> metrics = new ArrayList<>();
        boolean financial = "FINANCIAL".equals(ctx.sectorProfile);
        double peWeight = financial ? 20 : 30;
        double pctWeight = financial ? 20 : 30;
        double pbWeight = financial ? 50 : 20;
        double dyWeight = financial ? 10 : 20;

        BigDecimal pe = ctx.stockPe();
        metrics.add(ScoreMetric.of("pe_ttm", "PE(TTM)", pe, fmt(pe, ""),
                pe != null && pe.signum() > 0 ? ScoreAnchors.lowerBetter(pe, 60, 30, 12) : null,
                peWeight,
                "东财估值分析同一序列的最新 PE；≤0 表示亏损或数据缺失，不参与打分",
                "ev:val:pe_ttm"));

        ValuationMath.Percentiles percentiles = ValuationMath.compute(ctx.valuations);
        BigDecimal pePercentile = ctx.stockPe == null || ctx.peObservationDate == null ? null
                : ValuationMath.latestPePercentile(percentiles, ctx.peObservationDate);
        metrics.add(ScoreMetric.of("pe_percentile", "PE 历史分位", pePercentile,
                fmt(pePercentile, "%"),
                ScoreAnchors.lowerBetter(pePercentile, 90, 50, 20), pctWeight,
                "在同一段 PE 历史上滚动计算，窗口自 " + iso(percentiles.windowFrom()) + " 起",
                "ev:val:pe_percentile"));

        BigDecimal pb = ctx.stockPb();
        metrics.add(ScoreMetric.of("pb", "PB(MRQ)", pb, fmt(pb, ""),
                pb != null && pb.signum() > 0 ? ScoreAnchors.lowerBetter(pb, 8, 3, 1.2) : null,
                pbWeight, "市净率；低于 0 没有经济含义", "ev:val:pb"));

        BigDecimal dividend = ctx.latestValuation == null ? null
                : ctx.latestValuation.dividendYield();
        metrics.add(ScoreMetric.of("dividend_yield", "股息率", dividend,
                fmt(dividend, "%"), ScoreAnchors.higherBetter(dividend, 0, 1.5, 4.0),
                dyWeight, "东财估值分析的最新股息率", "ev:val:dividend"));
        return DimensionScore.of("valuation", "估值", 22, metrics, List.of());
    }

    private static DimensionScore growthDimension(Context ctx) {
        WarehouseRows.FinancialReport latest = ctx.latestFinancial;
        List<ScoreMetric> metrics = new ArrayList<>();
        metrics.add(ScoreMetric.of("revenue_yoy", "营收同比",
                latest == null ? null : latest.revenueYoy(), fmt(latest == null ? null : latest.revenueYoy(), "%"),
                ScoreAnchors.higherBetter(latest == null ? null : latest.revenueYoy(),
                        -15, 5, 25), 30,
                "最近一期财报的营收同比", "ev:grow:revenue_yoy"));
        metrics.add(ScoreMetric.of("profit_yoy", "归母净利同比",
                latest == null ? null : latest.netProfitYoy(),
                fmt(latest == null ? null : latest.netProfitYoy(), "%"),
                ScoreAnchors.higherBetter(latest == null ? null : latest.netProfitYoy(),
                        -20, 5, 30), 35,
                "最近一期财报的归母净利润同比", "ev:grow:profit_yoy"));

        BigDecimal revenueCagr = cagr(ctx.annualReports, true);
        metrics.add(ScoreMetric.of("revenue_cagr_3y", "营收 3 年 CAGR", revenueCagr,
                fmt(revenueCagr, "%"), ScoreAnchors.higherBetter(revenueCagr, -5, 8, 25),
                20, "仅用年报端点计算，至少需要三年前的年度报告", "ev:grow:revenue_cagr"));
        BigDecimal profitCagr = cagr(ctx.annualReports, false);
        metrics.add(ScoreMetric.of("profit_cagr_3y", "净利 3 年 CAGR", profitCagr,
                fmt(profitCagr, "%"), ScoreAnchors.higherBetter(profitCagr, -10, 8, 25),
                15, "仅用年报端点计算；期间任一端点亏损则不计算", "ev:grow:profit_cagr"));

        List<String> reasons = new ArrayList<>();
        if (latest == null) {
            reasons.add("本地还没有可用的财务报告，成长维度暂不计分");
        }
        return DimensionScore.of("growth", "成长", 18, metrics, reasons);
    }

    private static DimensionScore qualityDimension(Context ctx) {
        WarehouseRows.FinancialReport latest = ctx.latestFinancial;
        boolean financial = "FINANCIAL".equals(ctx.sectorProfile);
        boolean realEstate = "REAL_ESTATE".equals(ctx.sectorProfile);
        List<ScoreMetric> metrics = new ArrayList<>();
        metrics.add(ScoreMetric.of("roe", "ROE", value(latest, "roe"), fmt(value(latest, "roe"), "%"),
                ScoreAnchors.higherBetter(value(latest, "roe"), 3, 9, 20),
                financial ? 100 : 35, "最近一期财报的净资产收益率", "ev:qual:roe"));
        if (!financial) {
            if (!realEstate) {
                metrics.add(ScoreMetric.of("gross_margin", "毛利率", value(latest, "gross"),
                        fmt(value(latest, "gross"), "%"),
                        ScoreAnchors.higherBetter(value(latest, "gross"), 10, 25, 45), 25,
                        "最近一期财报的毛利率", "ev:qual:gross_margin"));
                BigDecimal cfoRatio = ratio(latest == null ? null : latest.ocf(),
                        latest == null ? null : latest.netProfit(), true);
                metrics.add(ScoreMetric.of("cfo_to_profit", "经营现金流/净利", cfoRatio,
                        fmt(cfoRatio, "x"), ScoreAnchors.higherBetter(cfoRatio, 0.3, 0.8, 1.2),
                        25, "OCF ÷ 归母净利；净利≤0 时不计算", "ev:qual:cfo"));
            }
            BigDecimal receivableRatio = ratio(value(latest, "receivable"),
                    value(latest, "revenue"), true);
            metrics.add(ScoreMetric.of("receivable_to_revenue", "应收/营收", receivableRatio,
                    fmt(receivableRatio, "%"),
                    ScoreAnchors.lowerBetter(receivableRatio, 40, 20, 5), 15,
                    "应收账款 ÷ 营业收入", "ev:qual:receivable"));
        }
        return DimensionScore.of("quality", "盈利质量", 18, metrics, List.of());
    }

    private static DimensionScore healthDimension(Context ctx) {
        WarehouseRows.FinancialReport latest = ctx.latestFinancial;
        boolean healthNotMeaningful = "FINANCIAL".equals(ctx.sectorProfile)
                || "REAL_ESTATE".equals(ctx.sectorProfile);
        BigDecimal debtRatio = ratio(latest == null ? null : latest.totalLiabilities(),
                latest == null ? null : latest.totalAssets(), true);
        BigDecimal currentRatio = ratio(latest == null ? null : latest.currentAssets(),
                latest == null ? null : latest.currentLiabilities(), false);
        BigDecimal interestCoverage = latest == null || latest.interestExpense() == null
                || latest.interestExpense().signum() <= 0 || latest.ebit() == null
                ? null
                : latest.ebit().divide(latest.interestExpense(), 4, RoundingMode.HALF_UP);
        BigDecimal goodwillRatio = ratio(latest == null ? null : latest.goodwill(),
                latest == null ? null : latest.totalEquity(), true);

        List<ScoreMetric> metrics = List.of(
                ScoreMetric.of("debt_ratio", "资产负债率",
                        healthNotMeaningful ? null : debtRatio,
                        healthNotMeaningful ? null : fmt(debtRatio, "%"),
                        healthNotMeaningful ? null
                                : ScoreAnchors.lowerBetter(debtRatio, 75, 55, 35), 35,
                        healthNotMeaningful ? "金融/地产的负债结构不适用这一口径"
                                : "总负债 ÷ 总资产", "ev:health:debt"),
                ScoreMetric.of("current_ratio", "流动比率",
                        healthNotMeaningful ? null : currentRatio,
                        healthNotMeaningful ? null : fmt(currentRatio, "x"),
                        healthNotMeaningful ? null
                                : ScoreAnchors.higherBetter(currentRatio, 0.8, 1.2, 2.0), 25,
                        healthNotMeaningful ? "金融/地产的负债结构不适用这一口径"
                                : "流动资产 ÷ 流动负债", "ev:health:current"),
                ScoreMetric.of("interest_coverage", "利息保障倍数",
                        healthNotMeaningful ? null : interestCoverage,
                        healthNotMeaningful ? null : fmt(interestCoverage, "x"),
                        healthNotMeaningful ? null
                                : ScoreAnchors.higherBetter(interestCoverage, 1, 3, 8), 25,
                        healthNotMeaningful ? "金融企业没有可比的利息保障倍数"
                                : "EBIT ÷ 利息费用", "ev:health:interest"),
                ScoreMetric.of("goodwill_to_equity", "商誉/净资产",
                        healthNotMeaningful ? null : goodwillRatio,
                        healthNotMeaningful ? null : fmt(goodwillRatio, "%"),
                        healthNotMeaningful ? null
                                : ScoreAnchors.lowerBetter(goodwillRatio, 30, 10, 0), 15,
                        healthNotMeaningful ? "金融/地产不按商誉口径评估"
                                : "商誉 ÷ 净资产", "ev:health:goodwill"));

        List<String> reasons = new ArrayList<>();
        if (healthNotMeaningful) {
            reasons.add("该行业不使用通用的负债率/流动比率模板，这一维不计分");
        }
        return DimensionScore.of("health", "财务健康", 14, metrics, reasons);
    }

    private static DimensionScore technicalDimension(Context ctx, int weight) {
        TechnicalCalculator.TechnicalSummary t = ctx.technical;
        List<ScoreMetric> metrics = new ArrayList<>();
        metrics.add(ScoreMetric.of("price_percentile_1y", "1 年价格分位",
                t.pricePercentile1y(), fmt(t.pricePercentile1y(), "%"),
                ScoreAnchors.lowerBetter(t.pricePercentile1y(), 85, 50, 20),
                weight == 20 ? 20 : 40, "当前收盘价在过去 250 根日线中的分位（含等于）",
                "ev:tech:price_percentile"));

        BigDecimal vsMa250 = TechnicalCalculator.distanceFrom(ctx.price, t.ma250());
        BigDecimal maScore = ScoreAnchors.higherBetter(vsMa250, -20, 0, 12);
        if (maScore != null && vsMa250 != null && vsMa250.compareTo(BigDecimal.valueOf(35)) > 0) {
            // 过度延伸：趋势还在，但追高的风险不对称。
            maScore = maScore.multiply(BigDecimal.valueOf(0.8), MC).setScale(2, RoundingMode.HALF_UP);
        }
        metrics.add(ScoreMetric.of("vs_ma250", "收盘/MA250", vsMa250,
                fmt(vsMa250, "%"), maScore, weight == 20 ? 20 : 30,
                "偏离年线超过 +35% 时该子项 ×0.8", "ev:tech:ma250"));

        metrics.add(ScoreMetric.of("momentum60", "60 日动量", t.momentum60d(),
                fmt(t.momentum60d(), "%"),
                ScoreAnchors.higherBetter(t.momentum60d(), -20, 0, 15),
                weight == 20 ? 15 : 30,
                "最新收盘价相对 60 根之前收盘价的涨跌幅", "ev:tech:momentum60"));

        if (weight == 20) {
            metrics.add(ScoreMetric.of("main_flow_5d", "5 日主力净流入/成交额",
                    ctx.mainFlowRatio5d, fmt(ctx.mainFlowRatio5d, "%"),
                    ScoreAnchors.higherBetter(ctx.mainFlowRatio5d, -8, 0, 8), 20,
                    "最近 5 个交易日主力净流入合计 ÷ 同期成交额合计", "ev:tech:main_flow"));
            metrics.add(ScoreMetric.of("turnover", "换手率", ctx.turnover,
                    fmt(ctx.turnover, "%"),
                    ScoreAnchors.band(ctx.turnover, 0, 1, 5, 10), 15,
                    "1%–5% 为活跃且不过热；两端递减", "ev:tech:turnover"));
            BigDecimal amountYi = ctx.amount == null ? null
                    : ctx.amount.divide(YI, 4, RoundingMode.HALF_UP);
            metrics.add(ScoreMetric.of("amount", "成交额（亿）", amountYi,
                    fmt(amountYi, "亿"),
                    ScoreAnchors.higherBetter(amountYi, 0.5, 2, 10), 10,
                    "优先取 20 日均成交额，缺失时用最新行情成交额", "ev:tech:amount"));
        }
        return DimensionScore.of("technical", "技术资金", weight, metrics, List.of());
    }

    // ==================================================================
    // ETF / 指数
    // ==================================================================

    private static List<DimensionScore> etfDimensions(Context ctx) {
        List<DimensionScore> dimensions = new ArrayList<>();
        dimensions.add(indexValuationDimension(ctx, "valuation", "估值",
                30, 70, 30, 0, 0));
        dimensions.add(trendDimension(ctx, "趋势", 22));
        dimensions.add(liquidityDimension(ctx, 18));
        dimensions.add(etfSizeDimension(ctx));
        dimensions.add(structureDimension(ctx, "结构", 10));
        dimensions.add(etfTrackingDimension(ctx));
        return dimensions;
    }

    /**
     * 规模：优先基金净资产（季报披露），退回「最新价 × 份额」估算的总市值。
     *
     * <p>两者都可以衡量规模，但**不是同一个数**：AUM 是基金公司的季报口径，
     * 估算总市值会随溢价和份额申赎漂移。所以 basis 必须写清来源，页面上的
     * 「规模 90 亿」到底指哪个才读得懂。
     */
    private static DimensionScore etfSizeDimension(Context ctx) {
        FactsSnapshot.FundProfile profile = ctx.fundProfile;
        BigDecimal aumYi = profile == null || profile.aum() == null ? null
                : profile.aum().divide(YI, 4, RoundingMode.HALF_UP);
        FactsSnapshot.Quote quote = ctx.facts.quote().data();
        BigDecimal capYi = quote == null || quote.totalMarketCap() == null ? null
                : quote.totalMarketCap().divide(YI, 4, RoundingMode.HALF_UP);
        BigDecimal scaleYi = aumYi != null ? aumYi : capYi;
        String basis;
        if (aumYi != null) {
            basis = "基金净资产（截止 " + iso(profile.aumAsOf()) + "），越大越不容易被清盘";
        } else if (capYi != null) {
            basis = "按最新价与份额估算的总市值，不是基金净资产；越大越不容易被清盘";
        } else {
            basis = "没有基金净资产，也取不到可估算的总市值";
        }
        List<ScoreMetric> metrics = List.of(ScoreMetric.of("etf_aum", "基金规模（亿）",
                scaleYi, fmt(scaleYi, "亿"),
                ScoreAnchors.higherBetter(scaleYi, 2, 20, 200), 100, basis, "ev:fund:aum"));
        List<String> reasons = new ArrayList<>();
        if (scaleYi == null) {
            reasons.add(basis);
        }
        return DimensionScore.of("size", "规模", 12, metrics, reasons);
    }

    /**
     * 跟踪质量 = 年化跟踪误差（60%）+ 折溢价绝对值（40%）。
     *
     * <p>折溢价目前没有稳定的免费时间序列。它作为**带权重的不可用子指标**留着，
     * 是为了让该维覆盖率如实停在 0.6，页面显示 PARTIAL——而不是把缺项悄悄剔除、
     * 用一个只有跟踪误差的分数冒充完整结论。注意 {@code ScoreMetric.unavailable(...)}
     * 会把权重写成 0，表达不了这个语义，这里必须用规范构造。
     */
    private static DimensionScore etfTrackingDimension(Context ctx) {
        FactsSnapshot.FundProfile profile = ctx.fundProfile;
        BigDecimal te = profile == null ? null : profile.trackingErrorPct();
        String teBasis = profile == null || te == null
                ? "免费源未取到年化跟踪误差"
                : "年化跟踪误差，越小越好"
                        + (profile.peerTrackingErrorPct() == null ? ""
                        : "；同类平均 " + fmt(profile.peerTrackingErrorPct(), "%"))
                        + (profile.trackingErrorAsOf() == null ? ""
                        : "（截止 " + iso(profile.trackingErrorAsOf()) + "）");
        List<ScoreMetric> metrics = List.of(
                ScoreMetric.of("etf_tracking_error", "年化跟踪误差", te, fmt(te, "%"),
                        ScoreAnchors.lowerBetter(te, 3, 1.5, 0.5), 60, teBasis,
                        "ev:fund:tracking"),
                new ScoreMetric("etf_premium", "折溢价", null, null, null, 40,
                        "折溢价率没有稳定的免费时间序列，该子项不计分", "ev:etf:premium"));
        DimensionScore dimension = DimensionScore.of("tracking", "跟踪质量", 8, metrics, List.of());
        if (!dimension.available()) {
            return new DimensionScore(dimension.key(), dimension.label(), dimension.weight(),
                    null, BigDecimal.ZERO, dimension.metrics(),
                    List.of("免费源未取到年化跟踪误差，该维不计分"));
        }
        return dimension;
    }

    private static List<DimensionScore> indexDimensions(Context ctx) {
        List<DimensionScore> dimensions = new ArrayList<>();
        dimensions.add(indexValuationDimension(ctx, "valuation", "估值",
                32, 60, 0, 20, 20));
        dimensions.add(trendDimension(ctx, "趋势", 22));
        dimensions.add(structureDimension(ctx, "结构", 16));
        dimensions.add(indexScaleDimension(ctx));
        dimensions.add(ScoreResultFree.na("earnings", "成份盈利", 10,
                "指数成份股的 ROE / 净利增速中位数尚未接入", "ev:index:earnings"));
        dimensions.add(ScoreResultFree.na("industry", "行业景气", 5,
                "行业板块动量尚未接入快照", "ev:index:industry"));
        return dimensions;
    }

    /**
     * ETF 与指数的估值维共用一段逻辑，但子指标不同：
     * ETF 看「PE 分位 + 当前 PE / 历史中位数」，指数额外看股息率与 PB 分位。
     * 权重小于等于 0 的子指标**不会出现**在 metrics 里，避免页面显示一条永远 N/A 的行。
     */
    private static DimensionScore indexValuationDimension(Context ctx, String key, String label,
                                                          int classWeight, double pePctWeight,
                                                          double relativeWeight,
                                                          double dividendWeight,
                                                          double pbPctWeight) {
        FactsSnapshot.IndexValuation v = ctx.indexValuation;
        List<ScoreMetric> metrics = new ArrayList<>();
        BigDecimal percentile = v == null ? null : v.percentile();
        metrics.add(ScoreMetric.of("index_pe_percentile", "跟踪指数 PE 分位", percentile,
                fmt(percentile, "%"),
                ScoreAnchors.lowerBetter(percentile, 90, 50, 20), pePctWeight,
                v == null ? "没有匹配到指数估值口径"
                        : "口径：" + v.percentileMethod() + "（" + v.sourceLabel() + "）",
                "ev:index:pe_percentile"));

        if (relativeWeight > 0) {
            BigDecimal pe = v == null ? null : v.peTtm();
            BigDecimal median = v == null ? null : v.peMedian();
            BigDecimal relative = pe == null || median == null || median.signum() <= 0
                    ? null : pe.divide(median, 4, RoundingMode.HALF_UP);
            metrics.add(ScoreMetric.of("pe_to_median", "当前 PE / 历史中位数", relative,
                    fmt(relative, "x"),
                    ScoreAnchors.lowerBetter(relative, 1.5, 1.0, 0.7), relativeWeight,
                    "同一口径下的当前 PE ÷ 历史中位数", "ev:index:pe_median"));
        }

        if (dividendWeight > 0) {
            BigDecimal dividend = ctx.indexProfile == null
                    ? null : ctx.indexProfile.dividendYield();
            metrics.add(ScoreMetric.of("index_dividend", "指数股息率", dividend,
                    fmt(dividend, "%"), ScoreAnchors.higherBetter(dividend, 0, 1.5, 4.0),
                    dividendWeight, "指数画像里的股息率", "ev:index:dividend"));
        }

        if (pbPctWeight > 0) {
            BigDecimal pbPercentile = v == null ? null : v.pbPercentile();
            metrics.add(ScoreMetric.of("index_pb_percentile", "跟踪指数 PB 分位",
                    pbPercentile, fmt(pbPercentile, "%"),
                    ScoreAnchors.lowerBetter(pbPercentile, 90, 50, 20), pbPctWeight,
                    "用本地 PB 历史按同一滚动窗口计算；源没有 PB 时不计算",
                    "ev:index:pb_percentile"));
        }

        List<String> reasons = new ArrayList<>();
        if (v == null) {
            reasons.add("指数池没有为这只标的配置估值口径，估值维不计分");
        }
        if (dividendWeight > 0 && ctx.indexProfile == null) {
            reasons.add("指数画像尚未同步，股息率子项暂不计分");
        }
        return DimensionScore.of(key, label, classWeight, metrics, reasons);
    }

    private static DimensionScore trendDimension(Context ctx, String label, int weight) {
        TechnicalCalculator.TechnicalSummary t = ctx.technical;
        BigDecimal vsMa = TechnicalCalculator.distanceFrom(ctx.price, t.ma250());
        List<ScoreMetric> metrics = List.of(
                ScoreMetric.of("price_percentile_1y", "1 年价格分位", t.pricePercentile1y(),
                        fmt(t.pricePercentile1y(), "%"),
                        ScoreAnchors.lowerBetter(t.pricePercentile1y(), 85, 50, 20), 40,
                        "当前价在过去 250 根日线中的分位", "ev:tech:price_percentile"),
                ScoreMetric.of("vs_ma250", "收盘/MA250", vsMa, fmt(vsMa, "%"),
                        ScoreAnchors.higherBetter(vsMa, -15, 0, 10), 30,
                        "相对年线的偏离", "ev:tech:ma250"),
                ScoreMetric.of("momentum60", "60 日动量", t.momentum60d(),
                        fmt(t.momentum60d(), "%"),
                        ScoreAnchors.higherBetter(t.momentum60d(), -15, 0, 12), 30,
                        "相对 60 根之前收盘价的涨跌幅", "ev:tech:momentum60"));
        return DimensionScore.of("trend", label, weight, metrics, List.of());
    }

    private static DimensionScore liquidityDimension(Context ctx, int weight) {
        // ETF 的腾讯预取日线不带成交额，20 日均值常常为空；但当日行情里是有成交额的。
        // 完全留空会让「流动性」维度整块缺失、压低六维覆盖率，连位置与趋势的结论也一起被暂缓。
        // 当日值只作降级参考：权重减半、文案写明不是均值，避免把单日放量误当成常态活跃。
        boolean dailyOnly = ctx.avgAmount20 == null && ctx.currentAmount != null;
        BigDecimal amount = ctx.avgAmount20 != null ? ctx.avgAmount20 : ctx.currentAmount;
        BigDecimal amountYi = amount == null ? null
                : amount.divide(YI, 4, RoundingMode.HALF_UP);
        List<ScoreMetric> metrics = List.of(
                ScoreMetric.of("avg_amount_20d", dailyOnly ? "当日成交额（亿）" : "20 日均成交额（亿）",
                        amountYi, fmt(amountYi, "亿"),
                        ScoreAnchors.higherBetter(amountYi, 0.1, 1, 10), dailyOnly ? 30 : 50,
                        dailyOnly ? "20 日均值缺失，用当日成交额降级参考，不代表常态活跃度"
                                : "1000 万 / 1 亿 / 10 亿三档", "ev:etf:amount"),
                ScoreMetric.of("turnover", "换手率", ctx.turnover, fmt(ctx.turnover, "%"),
                        ScoreAnchors.band(ctx.turnover, 0, 1, 5, 10), 50,
                        "1%–5% 为活跃且不过热", "ev:etf:turnover"));
        return DimensionScore.of("liquidity", "流动性", weight, metrics, List.of());
    }

    private static DimensionScore structureDimension(Context ctx, String label, int weight) {
        BigDecimal top10 = ctx.indexProfile == null ? null : ctx.indexProfile.top10Weight();
        BigDecimal hhi = hhi(ctx.industryWeights);
        List<ScoreMetric> metrics = List.of(
                ScoreMetric.of("top10_weight", "前十大权重", top10, fmt(top10, "%"),
                        ScoreAnchors.lowerBetter(top10, 80, 50, 25), 50,
                        "权重越集中，单一成分股波动对指数的影响越大", "ev:index:top10"),
                ScoreMetric.of("industry_hhi", "行业集中度 HHI", hhi, fmt(hhi, ""),
                        ScoreAnchors.lowerBetter(hhi, 0.40, 0.20, 0.10), 50,
                        "按行业权重平方和计算；越低越分散", "ev:index:hhi"));
        return DimensionScore.of("structure", label, weight, metrics, List.of());
    }

    private static DimensionScore indexScaleDimension(Context ctx) {
        FactsSnapshot.Domain<WarehouseRows.IndexProfile> domain = ctx.facts.indexProfile();
        WarehouseRows.IndexProfile profile = domain.data();
        BigDecimal capYi = profile == null || profile.totalMarketCap() == null ? null
                : profile.totalMarketCap().divide(YI, 4, RoundingMode.HALF_UP);
        List<ScoreMetric> metrics = List.of(
                ScoreMetric.of("constituent_count", "成份数量",
                        profile == null ? null
                                : profile.constituentCount() == null ? null
                                : BigDecimal.valueOf(profile.constituentCount()),
                        profile == null || profile.constituentCount() == null ? null
                                : profile.constituentCount() + " 只",
                        ScoreAnchors.higherBetter(profile == null || profile.constituentCount() == null
                                ? null : BigDecimal.valueOf(profile.constituentCount()),
                                20, 100, 300), 40,
                        "成份越多，单一成分股的影响越小", "ev:index:constituents"),
                ScoreMetric.of("total_market_cap", "总市值（亿）", capYi, fmt(capYi, "亿"),
                        ScoreAnchors.higherBetter(capYi, 500, 3000, 10000), 30,
                        "指数画像的总市值", "ev:index:market_cap"),
                ScoreMetric.of("amount", "成交额（亿）",
                        ctx.avgAmount20 == null ? null
                                : ctx.avgAmount20.divide(YI, 4, RoundingMode.HALF_UP),
                        fmt(ctx.avgAmount20 == null ? null
                                : ctx.avgAmount20.divide(YI, 4, RoundingMode.HALF_UP), "亿"),
                        ScoreAnchors.higherBetter(ctx.avgAmount20 == null ? null
                                : ctx.avgAmount20.divide(YI, 4, RoundingMode.HALF_UP),
                                5, 30, 200), 30,
                        "指数的日均成交额（按对应 ETF/指数日线）", "ev:index:amount"));
        return DimensionScore.of("scale", "规模流动性", 15, metrics, List.of());
    }

    // ==================================================================
    // 硬约束与覆盖率
    // ==================================================================

    private static List<String> hardFlags(Context ctx, List<DimensionScore> dimensions) {
        List<String> flags = new ArrayList<>();
        String name = ctx.facts.identity().name();
        if (name != null) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.contains("ST") || name.contains("退")) {
                flags.add("ST_OR_DELISTING");
            }
        }
        if (ctx.latestFinancial != null && ctx.latestFinancial.totalEquity() != null
                && ctx.latestFinancial.totalEquity().signum() <= 0) {
            flags.add("NEGATIVE_EQUITY");
        }
        if (ctx.facts.instrument().isStock() && ctx.latestValuation != null
                && ctx.latestValuation.peTtm() != null
                && ctx.latestValuation.peTtm().signum() <= 0) {
            flags.add("LOSS_MAKING");
        }
        BigDecimal liquidity = ctx.currentAmount != null ? ctx.currentAmount : ctx.amount;
        if (liquidity != null && liquidity.compareTo(BigDecimal.valueOf(50_000_000L)) < 0) {
            flags.add("LOW_LIQUIDITY");
        }
        if (ctx.priceAsOf != null && tradingDaysBetween(ctx.priceAsOf, ctx.today) > 5) {
            flags.add("PRICE_STALE");
        }
        LocalDate listDate = ctx.facts.identity().listDate();
        if (listDate != null && tradingDaysBetween(listDate, ctx.today) < 120) {
            flags.add("SHORT_HISTORY");
        }
        return flags;
    }

    static BigDecimal coverage(List<DimensionScore> dimensions) {
        BigDecimal sum = BigDecimal.ZERO;
        for (DimensionScore dimension : dimensions) {
            BigDecimal dimCoverage = dimension.coverage() == null
                    ? BigDecimal.ZERO : dimension.coverage();
            sum = sum.add(dimCoverage.multiply(BigDecimal.valueOf(dimension.weight())));
        }
        return sum.divide(HUNDRED, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal weightedScore(List<DimensionScore> dimensions) {
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        for (DimensionScore dimension : dimensions) {
            if (!dimension.available()) {
                continue;
            }
            weighted = weighted.add(dimension.score()
                    .multiply(BigDecimal.valueOf(dimension.weight())));
            weights = weights.add(BigDecimal.valueOf(dimension.weight()));
        }
        if (weights.signum() == 0) {
            return null;
        }
        return weighted.divide(weights, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal confidence(Context ctx, BigDecimal coverage,
                                         List<DimensionScore> dimensions) {
        double freshness = 1.0;
        freshness = Math.min(freshness, priceFreshness(ctx.priceAsOf, ctx.today));
        freshness = Math.min(freshness, dateFreshness(ctx.valuationAsOf, ctx.today, 7, 30));
        freshness = Math.min(freshness,
                dateFreshness(ctx.financialAsOf, ctx.today, 180, 365));
        double sourceFactor = switch (ctx.quoteProvider == null ? "" : ctx.quoteProvider) {
            case "eastmoney", "csindex", "danjuan", "local" -> 1.0;
            case "tencent", "sina" -> 0.9;
            default -> 0.95;
        };
        BigDecimal value = coverage
                .multiply(BigDecimal.valueOf(freshness * sourceFactor), MC)
                .setScale(4, RoundingMode.HALF_UP);
        if (ctx.facts.instrument().isStock()
                && ctx.facts.identity().listDate() != null
                && tradingDaysBetween(ctx.facts.identity().listDate(), ctx.today) < 120) {
            value = value.min(BigDecimal.valueOf(0.6));
        }
        return value;
    }

    private static double priceFreshness(LocalDate priceAsOf, LocalDate today) {
        if (priceAsOf == null) return 0.5;
        int days = tradingDaysBetween(priceAsOf, today);
        return days <= 1 ? 1.0 : days <= 3 ? 0.9 : 0.5;
    }

    private static double dateFreshness(LocalDate asOf, LocalDate today, int goodDays,
                                        int okayDays) {
        if (asOf == null) return 0.5;
        long days = Math.max(0, ChronoUnit.DAYS.between(asOf, today));
        return days <= goodDays ? 1.0 : days <= okayDays ? 0.8 : 0.5;
    }

    // ==================================================================
    // 输入上下文与计算小工具
    // ==================================================================

    private static final class Context {
        final FactsSnapshot facts;
        final LocalDate today;
        final TechnicalCalculator.TechnicalSummary technical;
        final BigDecimal price;
        final LocalDate priceAsOf;
        final LocalDate valuationAsOf;
        /** 最后一个 PE 为正的交易日；当前 PE 为负时不拿旧分位冒充当前分位。 */
        final LocalDate peObservationDate;
        final LocalDate financialAsOf;
        final List<WarehouseRows.ValuationPoint> valuations;
        final WarehouseRows.ValuationPoint latestValuation;
        final BigDecimal stockPe;
        final BigDecimal stockPb;
        final WarehouseRows.FinancialReport latestFinancial;
        final List<WarehouseRows.FinancialReport> annualReports;
        final FactsSnapshot.IndexValuation indexValuation;
        /** ETF 的基金档案；个股/指数为 null。规模与跟踪质量从这里取数。 */
        final FactsSnapshot.FundProfile fundProfile;
        final WarehouseRows.IndexProfile indexProfile;
        final List<WarehouseRows.IndustryWeight> industryWeights;
        final BigDecimal avgAmount20;
        /** 当日成交额（有报价时）——流动性硬约束看这个，不看 20 日均值。 */
        final BigDecimal currentAmount;
        final BigDecimal amount;
        final BigDecimal turnover;
        final BigDecimal mainFlowRatio5d;
        final String sectorProfile;
        final String quoteProvider;

        Context(FactsSnapshot facts, LocalDate today) {
            this.facts = facts;
            this.today = today;
            this.technical = TechnicalCalculator.compute(facts.prices().data());
            FactsSnapshot.Quote quote = facts.quote().data();
            this.price = quote != null && quote.price() != null ? quote.price()
                    : technical.price();
            // 新鲜度看报价所属交易日；没有报价才退回最后一根日线。取较晚者会把停牌掩盖掉。
            this.priceAsOf = quote != null && quote.asOf() != null
                    ? quote.asOf() : technical.lastDate();
            this.quoteProvider = quote == null ? null : quote.provider();

            List<WarehouseRows.ValuationPoint> valuationRows = facts.valuations().data();
            this.valuations = valuationRows == null ? List.of() : valuationRows;
            this.latestValuation = latestPoint(this.valuations);
            this.valuationAsOf = latestValuation == null ? null : latestValuation.tradeDate();
            // 「当前」必须是最新一行的值：PE 转负时不能退回上一条正 PE 当作现在。
            this.stockPe = latestValuation == null ? null : positive(latestValuation.peTtm());
            this.stockPb = latestValuation == null ? null : positive(latestValuation.pbMrq());
            this.peObservationDate = latestPositivePeDate(this.valuations);

            List<WarehouseRows.FinancialReport> reports = facts.financials().data();
            this.latestFinancial = reports == null || reports.isEmpty() ? null : reports.get(0);
            this.financialAsOf = latestFinancial == null ? null : latestFinancial.reportDate();
            List<WarehouseRows.FinancialReport> annual = new ArrayList<>();
            if (reports != null) {
                for (WarehouseRows.FinancialReport report : reports) {
                    if (report.reportDate() != null && report.reportDate().getMonthValue() == 12) {
                        annual.add(report);
                    }
                }
            }
            this.annualReports = List.copyOf(annual);

            this.indexValuation = facts.indexValuation().data();
            this.fundProfile = facts.fundProfile() == null ? null : facts.fundProfile().data();
            this.indexProfile = facts.indexProfile().data();
            List<WarehouseRows.IndustryWeight> weights = facts.industry().data();
            this.industryWeights = weights == null ? List.of() : weights;

            List<WarehouseRows.PriceBar> prices = facts.prices().data();
            this.avgAmount20 = average(prices, 20, true);
            BigDecimal quoteAmount = quote == null ? null : quote.amount();
            this.currentAmount = positive(quoteAmount);
            this.amount = avgAmount20 != null && avgAmount20.signum() > 0
                    ? avgAmount20 : currentAmount;
            BigDecimal quoteTurnover = quote == null ? null : quote.turnoverRate();
            BigDecimal averageTurnover = average(prices, 20, false);
            this.turnover = positive(quoteTurnover) != null ? quoteTurnover : averageTurnover;
            this.mainFlowRatio5d = mainFlowRatio(facts.moneyflow().data(), prices);
            this.sectorProfile = facts.identity().sectorProfile();
        }

        BigDecimal stockPe() {
            return stockPe;
        }

        BigDecimal stockPb() {
            return stockPb;
        }
    }

    private static BigDecimal average(List<WarehouseRows.PriceBar> prices, int n,
                                      boolean amount) {
        if (prices == null || prices.isEmpty()) return null;
        int from = Math.max(0, prices.size() - n);
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = from; i < prices.size(); i++) {
            BigDecimal value = amount ? prices.get(i).amount() : prices.get(i).turnoverRate();
            if (value != null) {
                sum = sum.add(value);
                count++;
            }
        }
        return count == 0 ? null
                : sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    /** 最近 5 个交易日主力净流入合计 ÷ 同期成交额合计（%）。 */
    private static BigDecimal mainFlowRatio(List<WarehouseRows.MoneyFlow> flows,
                                            List<WarehouseRows.PriceBar> prices) {
        if (flows == null || flows.size() < 5 || prices == null || prices.size() < 5) {
            return null;
        }
        BigDecimal main = BigDecimal.ZERO;
        for (int i = flows.size() - 5; i < flows.size(); i++) {
            BigDecimal value = flows.get(i).mainNet();
            if (value != null) main = main.add(value);
        }
        BigDecimal amount = BigDecimal.ZERO;
        for (int i = prices.size() - 5; i < prices.size(); i++) {
            BigDecimal value = prices.get(i).amount();
            if (value != null) amount = amount.add(value);
        }
        if (amount.signum() <= 0) {
            return null;
        }
        return main.multiply(HUNDRED).divide(amount, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal cagr(List<WarehouseRows.FinancialReport> annual, boolean revenue) {
        if (annual == null || annual.size() < 2) {
            return null;
        }
        WarehouseRows.FinancialReport latest = annual.get(0);
        WarehouseRows.FinancialReport earlier = null;
        for (int i = 1; i < annual.size(); i++) {
            WarehouseRows.FinancialReport candidate = annual.get(i);
            if (candidate.reportDate().getYear() <= latest.reportDate().getYear() - 3) {
                earlier = candidate;
                break;
            }
        }
        if (earlier == null) {
            return null;
        }
        BigDecimal end = revenue ? latest.revenue() : latest.netProfit();
        BigDecimal start = revenue ? earlier.revenue() : earlier.netProfit();
        if (end == null || start == null || end.signum() <= 0 || start.signum() <= 0) {
            return null;
        }
        int years = latest.reportDate().getYear() - earlier.reportDate().getYear();
        if (years <= 0) {
            return null;
        }
        double rate = Math.pow(end.doubleValue() / start.doubleValue(), 1.0 / years) - 1.0;
        return BigDecimal.valueOf(rate * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator,
                                    boolean percent) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return null;
        }
        BigDecimal value = numerator.divide(denominator, 6, RoundingMode.HALF_UP);
        return percent ? value.multiply(HUNDRED).setScale(4, RoundingMode.HALF_UP) : value;
    }

    private static BigDecimal hhi(List<WarehouseRows.IndustryWeight> weights) {
        if (weights == null || weights.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (WarehouseRows.IndustryWeight weight : weights) {
            if (weight.weight() == null || weight.weight().signum() < 0) continue;
            BigDecimal share = weight.weight().divide(HUNDRED, 8, RoundingMode.HALF_UP);
            sum = sum.add(share.multiply(share));
            count++;
        }
        return count == 0 ? null : sum.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal value(WarehouseRows.FinancialReport report, String field) {
        if (report == null) return null;
        return switch (field) {
            case "roe" -> report.roe();
            case "gross" -> report.grossMargin();
            case "receivable" -> report.accountsReceivable();
            case "revenue" -> report.revenue();
            default -> null;
        };
    }

    /** 最新一行（只要求日期存在）。PE 为负也是「当前」，不能跳过。 */
    private static WarehouseRows.ValuationPoint latestPoint(
            List<WarehouseRows.ValuationPoint> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            WarehouseRows.ValuationPoint p = points.get(i);
            if (p != null && p.tradeDate() != null) {
                return p;
            }
        }
        return null;
    }

    private static LocalDate latestPositivePeDate(
            List<WarehouseRows.ValuationPoint> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            WarehouseRows.ValuationPoint p = points.get(i);
            if (p != null && p.tradeDate() != null && positive(p.peTtm()) != null) {
                return p.tradeDate();
            }
        }
        return null;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return 0;
        }
        int days = 0;
        for (LocalDate date = from.plusDays(1); !date.isAfter(to); date = date.plusDays(1)) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private static String fmt(BigDecimal value, String unit) {
        if (value == null) return null;
        String text = value.stripTrailingZeros().toPlainString();
        return unit == null || unit.isEmpty() ? text : text + unit;
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String sectorLabel(String sectorProfile) {
        return switch (sectorProfile) {
            case "FINANCIAL" -> "金融";
            case "REAL_ESTATE" -> "地产";
            case "CYCLICAL" -> "周期";
            default -> "通用";
        };
    }

    /** 小工具：构造一个整维 N/A 的维度，避免六维的权重与文案写四遍。 */
    private static final class ScoreResultFree {
        private ScoreResultFree() {
        }

        static DimensionScore na(String key, String label, int weight, String reason,
                                 String evidenceId) {
            return new DimensionScore(key, label, weight, null, BigDecimal.ZERO,
                    List.of(ScoreMetric.unavailable(key, label, reason, evidenceId)),
                    List.of(reason));
        }
    }
}
