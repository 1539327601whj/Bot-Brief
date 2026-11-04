package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.screener.v2.TechnicalCalculator;
import com.ai.daily.screener.v2.ValuationMath;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码查询的决策层：**位置 → 时机 → 行动 → 长期/短期**。
 *
 * <p>纯函数：输入同一份 {@link FactsSnapshot}、评分、结论和参考区间，输出逐字段一致。
 * 不查库、不走网络、不读系统时钟。所有结论都能回到一个指标或一个硬约束上。
 *
 * <p>它回答的是用户真正会问的四个问题：
 * <ol>
 *   <li>现在处于什么位置？（估值分位 + 一年价格分位 + 趋势）</li>
 *   <li>现在是不是好时机？（位置 + 趋势，不预测涨跌）</li>
 *   <li>该怎么做？（分批建仓 / 持有观察 / 等待 / 回避 / 数据不足）</li>
 *   <li>长期还是短期更优？（两套权重分别打分后比较，差距 ≥10 才单给一侧）</li>
 * </ol>
 *
 * <p><b>行动标签是封闭集合</b>，不是让模型自由发挥的建议。数据不足时一律
 * {@code INSUFFICIENT}，不用「中性」或 0 分顶替。
 */
public final class DecisionEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal LOW_VALUATION = BigDecimal.valueOf(30);
    private static final BigDecimal HIGH_VALUATION = BigDecimal.valueOf(70);
    private static final BigDecimal LOW_PRICE = BigDecimal.valueOf(40);
    private static final BigDecimal HIGH_PRICE = BigDecimal.valueOf(70);
    private static final BigDecimal HORIZON_GAP = BigDecimal.valueOf(10);
    private static final BigDecimal TEN_PCT = BigDecimal.valueOf(10);
    private static final BigDecimal FIFTEEN_PCT = BigDecimal.valueOf(15);

    /**
     * 只有这两类旗标代表「标的本身不适合参与」。
     * 亏损、低流动性、上市时间短是风险，但 {@link VerdictEngine} 只做封顶/降档，
     * 决策层不能把它们直接升级成「回避」。
     */
    private static final Set<String> SEVERE_FLAGS = Set.of("ST_OR_DELISTING", "NEGATIVE_EQUITY");

    private static final Map<String, Double> STOCK_LONG = Map.of(
            "valuation", 25.0, "growth", 25.0, "quality", 25.0,
            "health", 15.0, "technical", 10.0);
    private static final Map<String, Double> STOCK_SHORT = Map.of(
            "valuation", 20.0, "technical", 50.0, "growth", 15.0, "quality", 15.0);
    private static final Map<String, Double> ETF_LONG = Map.of(
            "valuation", 30.0, "trend", 20.0, "liquidity", 10.0,
            "size", 15.0, "structure", 10.0, "tracking", 15.0);
    private static final Map<String, Double> ETF_SHORT = Map.of(
            "valuation", 25.0, "trend", 45.0, "liquidity", 15.0, "size", 15.0);
    private static final Map<String, Double> INDEX_LONG = Map.of(
            "valuation", 30.0, "trend", 20.0, "structure", 15.0,
            "size", 15.0, "earnings", 10.0, "industry", 10.0);
    private static final Map<String, Double> INDEX_SHORT = Map.of(
            "valuation", 25.0, "trend", 45.0, "structure", 10.0, "size", 20.0);

    private DecisionEngine() {
    }

    public static Decision evaluate(FactsSnapshot facts, ScoreResult score,
                                    Verdict verdict, BuyZone buyZone) {
        if (facts == null) {
            return Decision.insufficient("没有可用的标的快照", List.of(), List.of());
        }

        TechnicalCalculator.TechnicalSummary tech =
                TechnicalCalculator.compute(facts.prices() == null ? null : facts.prices().data());
        BigDecimal price = currentPrice(facts, tech);
        BigDecimal valuationPct = valuationPercentile(facts);
        BigDecimal pricePct = tech.pricePercentile1y();
        BigDecimal vsMa250 = TechnicalCalculator.distanceFrom(price, tech.ma250());
        BigDecimal momentum60 = tech.momentum60d();

        List<Decision.Axis> axes = List.of(
                valuationAxis(valuationPct, valuationBasis(facts)),
                priceAxis(pricePct),
                trendAxis(vsMa250, momentum60));

        List<Decision.Axis> availableAxes = axes.stream()
                .filter(axis -> "OK".equals(axis.status()))
                .toList();
        int positionScore = availableAxes.stream().mapToInt(Decision.Axis::score).sum();
        Decision.Position position = position(availableAxes.size(), positionScore, axes);

        boolean withheld = verdict == null || verdict.withheld();
        Decision.Timing timing = timing(position, axes, verdict, withheld);
        Decision.Action action = action(timing, buyZone, vsMa250, momentum60);
        Decision.Horizon horizon = horizon(facts, score, tech);
        Decision.LongTerm longTerm = longTerm(
                facts.prices() == null ? null : facts.prices().data());
        ConditionalReturnCalculator.Outlook outlook = ConditionalReturnCalculator.compute(
                facts.prices() == null ? null : facts.prices().data(), pricePct);

        List<Reason> risks = risks(facts, verdict, buyZone, valuationPct, pricePct,
                vsMa250, momentum60, position);
        List<String> watchItems = watchItems(axes, buyZone, vsMa250, momentum60);

        List<Reason> reasons = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Reason reason : position.reasons()) {
            if (seen.add(reason.text())) reasons.add(reason);
        }
        for (Reason reason : timing.reasons()) {
            if (seen.add(reason.text())) reasons.add(reason);
        }
        String summary = "位置：" + position.label()
                + " · 时机：" + timing.label()
                + " · 行动：" + action.label()
                + " · 周期：" + horizon.label();

        return new Decision(position, timing, action, horizon, longTerm, outlook,
                List.copyOf(reasons), List.copyOf(risks), List.copyOf(watchItems),
                summary, Decision.DISCLAIMER);
    }

    // ==================================================================
    // 位置
    // ==================================================================

    private static Decision.Position position(int availableAxes, int score,
                                              List<Decision.Axis> axes) {
        if (availableAxes < 2) {
            return new Decision.Position("INSUFFICIENT", "位置数据不足", availableAxes, score,
                    axes, List.of(Reason.plain(
                    "至少需要估值、价格、趋势中的两个轴才能判断位置，当前只有 "
                            + availableAxes + " 个")));
        }
        String key;
        String label;
        if (score >= 2) {
            key = "LOW";
            label = "相对低位";
        } else if (score <= -2) {
            key = "HIGH";
            label = "相对高位";
        } else {
            key = "NEUTRAL";
            label = "中性位置";
        }
        List<Reason> reasons = new ArrayList<>();
        for (Decision.Axis axis : axes) {
            if ("OK".equals(axis.status())) {
                reasons.add(Reason.of(axis.label() + "：" + axis.display(),
                        "ev:decision:" + axis.key()));
            }
        }
        return new Decision.Position(key, label, availableAxes, score, axes, List.copyOf(reasons));
    }

    private static Decision.Axis valuationAxis(BigDecimal percentile, String basis) {
        if (percentile == null) {
            return new Decision.Axis("valuation", "估值位置", null, "—", 0, "NA", basis);
        }
        int score = percentile.compareTo(LOW_VALUATION) <= 0 ? 1
                : percentile.compareTo(HIGH_VALUATION) <= 0 ? 0 : -1;
        String tone = score > 0 ? "偏低" : score < 0 ? "偏高" : "中性";
        return new Decision.Axis("valuation", "估值位置",
                plain(percentile), fmt(percentile, "%") + "（" + tone + "）",
                score, "OK", basis);
    }

    private static Decision.Axis priceAxis(BigDecimal percentile) {
        if (percentile == null) {
            return new Decision.Axis("price", "价格位置", null, "—", 0, "NA",
                    "日线不足 250 根，无法计算一年价格分位");
        }
        int score = percentile.compareTo(LOW_PRICE) <= 0 ? 1
                : percentile.compareTo(HIGH_PRICE) <= 0 ? 0 : -1;
        String tone = score > 0 ? "偏低" : score < 0 ? "偏高" : "中性";
        return new Decision.Axis("price", "价格位置",
                plain(percentile), fmt(percentile, "%") + "（" + tone + "）",
                score, "OK", "当前收盘价在过去 250 根日线中的分位");
    }

    private static Decision.Axis trendAxis(BigDecimal vsMa250, BigDecimal momentum60) {
        if (vsMa250 == null || momentum60 == null) {
            return new Decision.Axis("trend", "趋势位置", null, "—", 0, "NA",
                    "日线不足 250 根或缺少60 日动量，趋势轴不可用");
        }
        int score;
        if (vsMa250.signum() >= 0 && momentum60.signum() >= 0) {
            score = 1;
        } else if (vsMa250.signum() < 0 && momentum60.compareTo(TEN_PCT.negate()) <= 0) {
            score = -1;
        } else {
            score = 0;
        }
        String tone = score > 0 ? "向上" : score < 0 ? "向下" : "中性";
        return new Decision.Axis("trend", "趋势位置",
                "ma250=" + plain(vsMa250) + ";momentum60=" + plain(momentum60),
                "相对 MA250 " + signedPct(vsMa250) + "，60 日动量 " + signedPct(momentum60)
                        + "（" + tone + "）",
                score, "OK", "站上 MA250 且动量为正为向上；跌破 MA250 且动量 ≤ -10% 为向下");
    }

    // ==================================================================
    // 时机 / 行动
    // ==================================================================

    private static Decision.Timing timing(Decision.Position position, List<Decision.Axis> axes,
                                          Verdict verdict, boolean withheld) {
        if (withheld) {
            String reason = verdict == null ? "没有规则结论"
                    : verdict.withheldReason() == null ? "数据不足，暂不评级"
                    : verdict.withheldReason();
            return new Decision.Timing("INSUFFICIENT", "数据不足",
                    List.of(Reason.plain(reason)),
                    List.of("先补齐关键数据，再判断时机"));
        }
        if (verdict.hardFlags() != null && verdict.hardFlags().stream().anyMatch(SEVERE_FLAGS::contains)) {
            return new Decision.Timing("AVOID", "不宜参与",
                    List.of(Reason.plain("命中硬约束：" + String.join("、",
                            verdict.hardFlags().stream().filter(SEVERE_FLAGS::contains).toList()))),
                    List.of("硬风险解除后重新评估"));
        }
        if ("INSUFFICIENT".equals(position.key())) {
            return new Decision.Timing("INSUFFICIENT", "数据不足",
                    List.of(Reason.plain("位置轴不足，无法判断时机")),
                    List.of("先补齐估值、日线与财务数据"));
        }

        Decision.Axis trend = axes.stream()
                .filter(axis -> "trend".equals(axis.key()))
                .findFirst().orElse(null);
        boolean trendKnown = trend != null && "OK".equals(trend.status());
        boolean trendDown = trendKnown && trend.score() < 0;
        boolean trendUp = trendKnown && trend.score() >= 0;

        String key;
        String label;
        List<String> triggers = new ArrayList<>();
        switch (position.key()) {
            case "LOW" -> {
                if (trendUp) {
                    key = "ACCUMULATE";
                    label = "可分批关注";
                    triggers.add("价格重新站上 MA20 且 20 日动量转正");
                    triggers.add("估值分位保持在 30% 以下或继续下降");
                } else {
                    key = "WAIT_STABILIZE";
                    label = "等待企稳";
                    triggers.add("价格重新站上 MA250 且 60 日动量转正");
                    triggers.add("出现放量止跌或估值分位继续下降");
                }
            }
            case "NEUTRAL" -> {
                if (trendDown) {
                    // 位置不算高、但价格仍在跌破 MA250 且动量为负：等企稳，
                    // 不是「什么都不做」。两者在行动上都是 WAIT，但标签不同，
                    // 用户看到的是「为什么等」。
                    key = "WAIT_STABILIZE";
                    label = "等待企稳";
                    triggers.add("价格重新站上 MA250 且 60 日动量转正");
                    triggers.add("出现放量止跌或估值分位回到 30% 以下");
                } else if (trendUp) {
                    key = "HOLD";
                    label = "持有观察";
                    triggers.add("估值分位回到 30% 以下再评估加仓");
                    triggers.add("价格跌破 MA250 时降低仓位");
                } else {
                    key = "WAIT";
                    label = "等待";
                    triggers.add("价格重新站上 MA250 且 60 日动量转正");
                    triggers.add("估值分位回到 30% 以下");
                }
            }
            default -> {
                key = "WAIT_PULLBACK";
                label = "等待回落";
                triggers.add("估值分位回落到 50% 以下");
                triggers.add("价格回到一年价格分位 50% 以下");
            }
        }
        if (!trendKnown) {
            triggers.add("补齐 250 根以上日线后重新判断趋势");
        }
        triggers.add("出现新的重大负面公告");

        List<Reason> reasons = new ArrayList<>(position.reasons());
        if (trendKnown) {
            reasons.add(Reason.of("趋势：" + trend.display(), "ev:decision:trend"));
        } else {
            reasons.add(Reason.plain("趋势数据不足，时机判断只依赖估值与价格位置"));
        }
        return new Decision.Timing(key, label, List.copyOf(reasons), List.copyOf(triggers));
    }

    private static Decision.Action action(Decision.Timing timing, BuyZone buyZone,
                                          BigDecimal vsMa250, BigDecimal momentum60) {
        String key;
        String label;
        switch (timing.key()) {
            case "ACCUMULATE" -> {
                key = "BUILD_GRADUALLY";
                label = "分批建仓";
            }
            case "HOLD" -> {
                key = "HOLD_OBSERVE";
                label = "持有观察";
            }
            case "WAIT_STABILIZE", "WAIT_PULLBACK" -> {
                key = "WAIT";
                label = "等待";
            }
            case "AVOID" -> {
                key = "AVOID";
                label = "回避";
            }
            default -> {
                key = "INSUFFICIENT";
                label = "数据不足";
            }
        }

        List<String> plan = new ArrayList<>();
        List<String> invalidation = new ArrayList<>();
        BigDecimal low = buyZone == null ? null : buyZone.low();
        BigDecimal high = buyZone == null ? null : buyZone.high();
        BigDecimal stop = buyZone == null ? null : buyZone.stopLoss();

        switch (key) {
            case "BUILD_GRADUALLY" -> {
                plan.add("首笔不超过计划仓位的 1/3，只在参考区间内执行");
                if (low != null && high != null) {
                    plan.add("参考区间 " + num(low) + "–" + num(high));
                } else {
                    plan.add("参考区间暂不可用，先按估值分位与 MA250 观察");
                }
                if (stop != null) {
                    plan.add("跌破 " + num(stop) + " 暂停计划");
                }
                plan.add("若价格跌破 MA250 且 60 日动量低于 -10%，改为等待");
            }
            case "HOLD_OBSERVE" -> {
                plan.add("已有仓位按参考止损位管理，不追高");
                if (stop != null) {
                    plan.add("跌破 " + num(stop) + " 时减仓或离场");
                } else {
                    plan.add("参考止损位暂不可用，用 MA250 作为观察线");
                }
            }
            case "WAIT" -> {
                plan.add("不新增仓位，等待触发条件");
                plan.add("触发条件见「什么会改变结论」");
            }
            case "AVOID" -> {
                plan.add("不参与，先排除硬风险");
                plan.add("硬风险解除后重新查询");
            }
            default -> {
                plan.add("先补齐关键数据，再做判断");
            }
        }

        if (stop != null) {
            invalidation.add("跌破参考止损 " + num(stop));
        }
        if (vsMa250 != null && momentum60 != null) {
            invalidation.add("价格跌破 MA250 且 60 日动量低于 -10%");
        }
        invalidation.add("出现新的重大负面公告");
        if ("AVOID".equals(key)) {
            invalidation.add("硬风险解除后重新评估");
        }

        return new Decision.Action(key, label, List.copyOf(plan), List.copyOf(invalidation));
    }

    // ==================================================================
    // 长期 / 短期
    // ==================================================================

    private static Decision.Horizon horizon(FactsSnapshot facts, ScoreResult score,
                                             TechnicalCalculator.TechnicalSummary tech) {
        if (score == null || score.finalScore() == null) {
            return new Decision.Horizon("INSUFFICIENT", "数据不足", null, null, null,
                    "没有可用的六维评分，无法比较长期与短期");
        }
        String assetType = facts.instrument() == null ? "STOCK" : facts.instrument().assetType();
        Map<String, Double> longWeights;
        Map<String, Double> shortWeights;
        switch (assetType) {
            case "ETF" -> {
                longWeights = ETF_LONG;
                shortWeights = ETF_SHORT;
            }
            case "INDEX" -> {
                longWeights = INDEX_LONG;
                shortWeights = INDEX_SHORT;
            }
            default -> {
                longWeights = STOCK_LONG;
                shortWeights = STOCK_SHORT;
            }
        }

        BigDecimal longBase = blend(score, longWeights);
        BigDecimal shortBase = blend(score, shortWeights);
        if (longBase == null || shortBase == null) {
            return new Decision.Horizon("INSUFFICIENT", "数据不足", null, null, null,
                    "长期或短期的可用维度不足，暂不比较");
        }

        BigDecimal longScore = clamp(longBase.add(longAdjustment(facts)));
        BigDecimal shortScore = clamp(shortBase.add(shortAdjustment(tech)));
        BigDecimal gap = longScore.subtract(shortScore);

        String key;
        String label;
        String summary;
        if (gap.compareTo(HORIZON_GAP) >= 0) {
            key = "LONG";
            label = "长期更优";
            summary = "长期分 " + num(longScore) + "，短期分 " + num(shortScore)
                    + "，长期更优：长期估值与趋势证据强于短期动量。";
        } else if (gap.compareTo(HORIZON_GAP.negate()) <= 0) {
            key = "SHORT";
            label = "短期更优";
            summary = "长期分 " + num(longScore) + "，短期分 " + num(shortScore)
                    + "，短期更优：短期趋势与动量强于长期证据。";
        } else {
            key = "BOTH";
            label = "两者接近";
            summary = "长期分 " + num(longScore) + "，短期分 " + num(shortScore)
                    + "，两者接近：长期与短期的证据没有明显优劣。";
        }
        return new Decision.Horizon(key, label, longScore, shortScore, gap, summary);
    }

    private static BigDecimal blend(ScoreResult score, Map<String, Double> weights) {
        double sum = 0;
        double weightSum = 0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            BigDecimal dimension = score.dimensionScore(entry.getKey());
            if (dimension == null) continue;
            sum += dimension.doubleValue() * entry.getValue();
            weightSum += entry.getValue();
        }
        if (weightSum <= 0) return null;
        return BigDecimal.valueOf(sum / weightSum).setScale(2, RoundingMode.HALF_UP);
    }

    /** 长期趋势修正：3 年 CAGR 与「高回撤且长期不向上」的组合。 */
    private static BigDecimal longAdjustment(FactsSnapshot facts) {
        List<WarehouseRows.PriceBar> bars = facts.prices() == null ? null : facts.prices().data();
        BigDecimal cagr = cagr(bars, 3);
        BigDecimal adjustment = BigDecimal.ZERO;
        if (cagr != null) {
            double value = cagr.doubleValue();
            if (value >= 0.08) {
                adjustment = adjustment.add(BigDecimal.valueOf(5));
            } else if (value >= 0) {
                adjustment = adjustment.add(BigDecimal.valueOf(2));
            } else if (value >= -0.08) {
                adjustment = adjustment.subtract(BigDecimal.valueOf(5));
            } else {
                adjustment = adjustment.subtract(BigDecimal.valueOf(12));
            }
        }
        BigDecimal drawdown = maxDrawdown(bars, 3);
        if (drawdown != null && drawdown.compareTo(BigDecimal.valueOf(-0.60)) <= 0
                && (cagr == null || cagr.signum() < 0)) {
            adjustment = adjustment.subtract(BigDecimal.valueOf(8));
        }
        return adjustment;
    }

    private static BigDecimal shortAdjustment(TechnicalCalculator.TechnicalSummary tech) {
        BigDecimal momentum = tech.momentum60d();
        if (momentum == null) return BigDecimal.ZERO;
        if (momentum.signum() >= 0) return BigDecimal.valueOf(3);
        if (momentum.compareTo(FIFTEEN_PCT.negate()) <= 0) return BigDecimal.valueOf(-8);
        if (momentum.compareTo(BigDecimal.valueOf(-8)) <= 0) return BigDecimal.valueOf(-4);
        return BigDecimal.ZERO;
    }

    // ==================================================================
    // 风险与观察条件
    // ==================================================================

    private static List<Reason> risks(FactsSnapshot facts, Verdict verdict, BuyZone buyZone,
                                      BigDecimal valuationPct, BigDecimal pricePct,
                                      BigDecimal vsMa250, BigDecimal momentum60,
                                      Decision.Position position) {
        List<Reason> risks = new ArrayList<>();
        if (verdict != null && verdict.risks() != null) {
            risks.addAll(verdict.risks());
        }
        if (valuationPct == null) {
            risks.add(Reason.plain("缺少估值历史分位，位置判断少一个轴"));
        }
        if (pricePct == null) {
            risks.add(Reason.plain("缺少一年价格分位，位置判断少一个轴"));
        }
        if (vsMa250 == null || momentum60 == null) {
            risks.add(Reason.plain("日线不足 250 根或缺少 60 日动量，趋势轴不可用"));
        }
        if (buyZone != null && "WITHHELD".equals(buyZone.status())) {
            String note = buyZone.notes() == null || buyZone.notes().isEmpty()
                    ? "参考区间暂不可用"
                    : buyZone.notes().get(0);
            risks.add(Reason.plain("参考区间暂不可用：" + note));
        }
        if ("INSUFFICIENT".equals(position.key())) {
            risks.add(Reason.plain("位置轴不足，位置与时机结论都只能标为数据不足"));
        }
        List<WarehouseRows.PriceBar> bars = facts.prices() == null ? null : facts.prices().data();
        if (cagr(bars, 3) == null) {
            risks.add(Reason.plain("本地日线不足 3 年，长期趋势只能参考现有区间"));
        }
        return List.copyOf(risks);
    }

    private static List<String> watchItems(List<Decision.Axis> axes, BuyZone buyZone,
                                           BigDecimal vsMa250, BigDecimal momentum60) {
        Set<String> items = new LinkedHashSet<>();
        Decision.Axis valuation = axes.stream()
                .filter(axis -> "valuation".equals(axis.key()))
                .findFirst().orElse(null);
        if (valuation != null && "OK".equals(valuation.status())) {
            if (valuation.score() > 0) {
                items.add("估值分位升破 70% 时重新评估");
            } else if (valuation.score() < 0) {
                items.add("估值分位回落到 50% 以下再评估");
            } else {
                items.add("估值分位升破 70% 或跌破 30% 时重新评估");
            }
        } else {
            items.add("补齐估值历史后重新判断位置");
        }
        items.add("价格重新站上 MA20 且 20 日动量转正");
        if (vsMa250 != null && momentum60 != null) {
            items.add("价格跌破 MA250 且 60 日动量低于 -10%");
        }
        if (buyZone != null && buyZone.low() != null && buyZone.high() != null) {
            items.add("价格回到参考区间 " + num(buyZone.low()) + "–" + num(buyZone.high()));
        }
        if (buyZone != null && buyZone.stopLoss() != null) {
            items.add("跌破参考止损 " + num(buyZone.stopLoss()));
        }
        items.add("出现新的重大负面公告");
        return List.copyOf(items);
    }

    // ==================================================================
    // 数据读取
    // ==================================================================

    private static String valuationBasis(FactsSnapshot facts) {
        if (facts.instrument() != null && facts.instrument().isStock()) {
            return "东财估值分析同一 PE 序列的滚动分位";
        }
        FactsSnapshot.IndexValuation valuation =
                facts.indexValuation() == null ? null : facts.indexValuation().data();
        if (valuation == null) {
            return "该标的没有配置指数 PE 估值口径";
        }
        return "指数 PE 分位口径：" + valuation.percentileMethod()
                + "（" + valuation.sourceLabel() + "）";
    }

    private static BigDecimal valuationPercentile(FactsSnapshot facts) {
        if (facts.instrument() == null) return null;
        if (facts.instrument().isStock()) {
            List<WarehouseRows.ValuationPoint> points =
                    facts.valuations() == null ? null : facts.valuations().data();
            if (points == null || points.isEmpty()) return null;
            ValuationMath.Percentiles percentiles = ValuationMath.compute(points);
            LocalDate date = latestPositivePeDate(points);
            return date == null ? null : ValuationMath.latestPePercentile(percentiles, date);
        }
        FactsSnapshot.IndexValuation valuation =
                facts.indexValuation() == null ? null : facts.indexValuation().data();
        return valuation == null ? null : valuation.percentile();
    }

    private static LocalDate latestPositivePeDate(List<WarehouseRows.ValuationPoint> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            WarehouseRows.ValuationPoint point = points.get(i);
            if (point != null && point.tradeDate() != null
                    && point.peTtm() != null && point.peTtm().signum() > 0) {
                return point.tradeDate();
            }
        }
        return null;
    }

    private static BigDecimal currentPrice(FactsSnapshot facts,
                                           TechnicalCalculator.TechnicalSummary tech) {
        FactsSnapshot.Quote quote = facts.quote() == null ? null : facts.quote().data();
        if (quote != null && quote.price() != null && quote.price().signum() > 0) {
            return quote.price();
        }
        return tech.price();
    }

    /** 3 年价格 CAGR，返回小数（0.08 = 8%）；历史不足时 null。 */
    static BigDecimal cagr(List<WarehouseRows.PriceBar> input, int years) {
        List<WarehouseRows.PriceBar> bars = clean(input);
        if (bars.size() < 2) return null;
        WarehouseRows.PriceBar last = bars.get(bars.size() - 1);
        LocalDate target = last.tradeDate().minusYears(years);
        WarehouseRows.PriceBar baseline = null;
        for (WarehouseRows.PriceBar bar : bars) {
            if (bar.tradeDate().isAfter(target)) break;
            baseline = bar;
        }
        if (baseline == null) return null;
        long days = ChronoUnit.DAYS.between(baseline.tradeDate(), last.tradeDate());
        if (days < 365L * years * 4 / 5) return null;
        double start = baseline.closePrice().doubleValue();
        double end = last.closePrice().doubleValue();
        if (start <= 0 || end <= 0) return null;
        double annual = Math.pow(end / start, 365.25 / days) - 1.0;
        return BigDecimal.valueOf(annual).setScale(4, RoundingMode.HALF_UP);
    }

    /** 最近 N 年的最大回撤，返回负数（-0.35 = -35%）；历史不足时 null。 */
    static BigDecimal maxDrawdown(List<WarehouseRows.PriceBar> input, int years) {
        List<WarehouseRows.PriceBar> bars = clean(input);
        if (bars.size() < 2) return null;
        LocalDate cutoff = bars.get(bars.size() - 1).tradeDate().minusYears(years);
        List<WarehouseRows.PriceBar> window = bars.stream()
                .filter(bar -> !bar.tradeDate().isBefore(cutoff))
                .toList();
        if (window.size() < 2) return null;
        double peak = window.get(0).closePrice().doubleValue();
        double worst = 0;
        for (WarehouseRows.PriceBar bar : window) {
            double close = bar.closePrice().doubleValue();
            if (close > peak) {
                peak = close;
            } else if (peak > 0) {
                worst = Math.min(worst, close / peak - 1.0);
            }
        }
        return BigDecimal.valueOf(worst).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 本标的自己的长期趋势。**样本不足就留空**：3 年 / 5 年年化分别要求
     * 对应的日历跨度，不能用一年的涨幅年化后冒充。
     */
    static Decision.LongTerm longTerm(List<WarehouseRows.PriceBar> input) {
        BigDecimal c3 = pct(cagr(input, 3));
        BigDecimal c5 = pct(cagr(input, 5));
        // 「3 年最大回撤」也必须真的覆盖 3 年；否则一段 8 个月的横盘会被算成 0% 回撤。
        BigDecimal drawdown = coversYears(input, 3) ? maxDrawdown(input, 3) : null;
        BigDecimal ddPct = drawdown == null ? null : pct(drawdown.abs());
        Boolean recovered = drawdown == null ? null : recovered(input, 3);

        if (c3 == null && c5 == null && ddPct == null) {
            return Decision.LongTerm.insufficient("本地日线不足 3 年，长期趋势未确认");
        }

        String key;
        String label;
        if (c3 != null && c3.signum() <= 0) {
            key = "FLAT_OR_DOWN";
            label = "长期走平或向下";
        } else if (c3 != null && c3.signum() > 0 && c5 != null && c5.signum() < 0) {
            key = "MIXED";
            label = "近 3 年转正，5 年仍未向上";
        } else {
            key = "UP";
            label = "长期向上";
        }

        List<String> parts = new ArrayList<>();
        if (c3 != null) parts.add("近 3 年年化 " + signedPct(c3));
        if (c5 != null) parts.add("近 5 年年化 " + signedPct(c5));
        if (ddPct != null) {
            parts.add("近 3 年最大回撤 " + fmt(ddPct, "%")
                    + (Boolean.TRUE.equals(recovered) ? "，已回到回撤前高点"
                    : "，尚未回到回撤前高点"));
        }
        return new Decision.LongTerm(key, label, c3, c5, ddPct, recovered,
                String.join("；", parts));
    }

    /** 日线首尾的日历跨度是否覆盖 N 年的 80%。 */
    static boolean coversYears(List<WarehouseRows.PriceBar> input, int years) {
        List<WarehouseRows.PriceBar> bars = clean(input);
        if (bars.size() < 2) return false;
        long days = ChronoUnit.DAYS.between(bars.get(0).tradeDate(),
                bars.get(bars.size() - 1).tradeDate());
        return days >= 365L * years * 4 / 5;
    }

    /** 最近 N 年的那次最大回撤之后，收盘价是否回到回撤前的高点。 */
    static Boolean recovered(List<WarehouseRows.PriceBar> input, int years) {
        List<WarehouseRows.PriceBar> bars = clean(input);
        if (bars.size() < 2) return null;
        LocalDate cutoff = bars.get(bars.size() - 1).tradeDate().minusYears(years);
        List<WarehouseRows.PriceBar> window = bars.stream()
                .filter(bar -> !bar.tradeDate().isBefore(cutoff))
                .toList();
        if (window.size() < 2) return null;
        double peak = window.get(0).closePrice().doubleValue();
        Double worstPeak = null;
        double worst = 0;
        boolean recovered = true;
        for (WarehouseRows.PriceBar bar : window) {
            double close = bar.closePrice().doubleValue();
            if (close > peak) peak = close;
            if (peak > 0 && close / peak - 1.0 < worst) {
                worst = close / peak - 1.0;
                worstPeak = peak;
                recovered = false;
            }
            if (worstPeak != null && close >= worstPeak) recovered = true;
        }
        return recovered;
    }

    /** 小数收益 → 百分数，保留 1 位；null 透传。 */
    private static BigDecimal pct(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP);
    }

    private static List<WarehouseRows.PriceBar> clean(List<WarehouseRows.PriceBar> input) {
        List<WarehouseRows.PriceBar> out = new ArrayList<>();
        if (input == null) return out;
        for (WarehouseRows.PriceBar bar : input) {
            if (bar != null && bar.tradeDate() != null && bar.closePrice() != null
                    && bar.closePrice().signum() > 0) {
                out.add(bar);
            }
        }
        out.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));
        return out;
    }

    // ==================================================================
    // 格式化
    // ==================================================================

    private static BigDecimal clamp(BigDecimal value) {
        if (value == null) return null;
        BigDecimal clamped = value.max(BigDecimal.ZERO).min(HUNDRED);
        return clamped.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—"
                : value.stripTrailingZeros().toPlainString();
    }

    private static String num(BigDecimal value) {
        if (value == null) return "—";
        return value.setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String fmt(BigDecimal value, String suffix) {
        return value == null ? "—" : num(value) + suffix;
    }

    private static String signedPct(BigDecimal value) {
        if (value == null) return "—";
        return (value.signum() >= 0 ? "+" : "") + num(value) + "%";
    }
}
