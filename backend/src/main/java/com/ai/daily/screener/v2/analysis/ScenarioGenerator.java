package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * 三情景卡：乐观 / 中性 / 悲观。
 *
 * <p><b>不显示概率、不给目标价、不给时间窗、不算隐含涨幅</b>。免费源没有可靠的
 * 盈利预测，把增速外推成价格会制造一个看似精确、实际不可信的数字。情景卡只回答：
 * 往哪个方向走、看什么信号验证、什么情况下这个判断失效。
 *
 * <p>三张卡永远同时返回；{@code triggered} 标出当前数据落在哪一张。没有触发的卡
 * 仍然保留，用户可以看到「要变成乐观情景需要满足什么」。
 *
 * <p>结论被 withhold（数据不足/停牌）时返回空列表——这时候画三张语气笃定的卡，
 * 比不给更糟。
 */
public final class ScenarioGenerator {

    private static final BigDecimal BULL_SCORE = BigDecimal.valueOf(65);
    private static final BigDecimal BEAR_SCORE = BigDecimal.valueOf(45);
    private static final BigDecimal GOOD_DIMENSION = BigDecimal.valueOf(55);
    private static final BigDecimal WEAK_TREND = BigDecimal.valueOf(35);

    private ScenarioGenerator() {
    }

    public static List<Scenario> generate(FactsSnapshot facts, ScoreResult score, Verdict verdict,
                                          BuyZone buyZone) {
        if (score == null || score.finalScore() == null
                || verdict == null || verdict.withheld()) {
            return List.of();
        }
        boolean indexLike = !facts.instrument().isStock();
        String valuationEvidence = indexLike
                ? "ev:index:pe_percentile" : "ev:val:pe_percentile";
        String momentumEvidence = "ev:tech:momentum60";
        String maEvidence = "ev:tech:ma250";

        BigDecimal valuation = score.dimensionScore("valuation");
        BigDecimal trend = score.dimensionScore(indexLike ? "trend" : "technical");
        boolean hasHardFlags = verdict.hardFlags() != null && !verdict.hardFlags().isEmpty();
        var research = facts.etfResearch();
        boolean equityResearch = research != null && research.gold() == null;
        boolean hasEarningsSupport = !equityResearch || (research.earnings().supported()
                && !research.earnings().sustainedContraction());

        boolean bull = score.finalScore().compareTo(BULL_SCORE) >= 0
                && atLeast(valuation, GOOD_DIMENSION)
                && atLeast(trend, GOOD_DIMENSION)
                && hasEarningsSupport
                && !hasHardFlags;
        boolean bear = !bull && (score.finalScore().compareTo(BEAR_SCORE) < 0
                || (trend != null && trend.compareTo(WEAK_TREND) < 0)
                || hasHardFlags);

        String bullNarrative = bull
                ? "估值 " + display(valuation) + " 分、趋势 " + display(trend)
                + " 分，两项同向；若盈利增速延续、估值分位不升破 80，当前状态偏积极。"
                : "估值与趋势同时站上 55 分、且没有硬约束时，才进入这一情景。";
        String baseNarrative = "估值与趋势未形成共振，等待方向确认；"
                + "区间上沿与下沿的突破方向比单日涨跌更有信息量。";
        if (!hasEarningsSupport) {
            bullNarrative = "先等同一期成分股财报确认盈利支撑，再看估值和价格趋势能否同时改善。";
            baseNarrative = "继续观察价格与量能。现有成分股数据尚未验证盈利支撑，下一期财报是复核条件。";
        }
        String bearNarrative = bear
                ? (hasHardFlags
                ? "存在硬约束（详见风险清单），结论已封顶；若再跌破参考止损，弱势可能延续。"
                : "趋势或估值至少一项偏弱；若跌破参考止损，弱势可能延续。")
                : "趋势转弱或跌破参考止损时，才进入这一情景。";

        BigDecimal stop = buyZone == null ? null : buyZone.stopLoss();
        String stopText = stop == null ? "参考止损" : "参考止损 " + display(stop);

        return List.of(
                new Scenario("bull", "乐观", bull,
                        "总分 ≥ 65、估值 ≥ 55、趋势 ≥ 55、无硬约束"
                                + (equityResearch ? "，成分股样本盈利有支撑" : ""),
                        bullNarrative,
                        List.of(valuationEvidence, maEvidence),
                        "PE 分位升破 80，或收盘跌破" + stopText,
                        Scenario.DISCLAIMER),
                new Scenario("base", "中性", !bull && !bear,
                        "总分 45–64，或估值与趋势方向相反"
                                + (equityResearch ? "，或盈利支撑待验证" : ""),
                        baseNarrative,
                        List.of(valuationEvidence, "ev:tech:price_percentile"),
                        "放量突破参考区间上沿或跌破下沿",
                        Scenario.DISCLAIMER),
                new Scenario("bear", "悲观", bear,
                        "总分 < 45，或趋势 < 35，或存在硬约束",
                        bearNarrative,
                        List.of(momentumEvidence, valuationEvidence),
                        "重新站上 MA250，且估值分位回落至 50 以下",
                        Scenario.DISCLAIMER));
    }

    private static boolean atLeast(BigDecimal value, BigDecimal threshold) {
        return value != null && value.compareTo(threshold) >= 0;
    }

    private static String display(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
