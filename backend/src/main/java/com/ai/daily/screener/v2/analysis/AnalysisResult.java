package com.ai.daily.screener.v2.analysis;

import java.util.List;

/**
 * 一份快照对应的完整规则分析：分数、结论、参考区间、决策、情景。
 *
 * <p>全部由 {@link InvestmentScoreModel} / {@link VerdictEngine} /
 * {@link BuyZoneCalculator} / {@link DecisionEngine} / {@link ScenarioGenerator} 从
 * {@code FactsSnapshot} 纯函数算出，**不读当前时间、不查库、不调用大模型**。
 */
public record AnalysisResult(
        ScoreResult score,
        Verdict verdict,
        BuyZone buyZone,
        Decision decision,
        List<Scenario> scenarios,
        String rulesVersion
) {
}
