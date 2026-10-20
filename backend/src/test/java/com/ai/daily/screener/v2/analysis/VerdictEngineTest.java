package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 结论标签、封顶、withheld 与公告事件调整。 */
class VerdictEngineTest {

    @Test
    void labelsFollowTheFiveBands() {
        assertThat(VerdictEngine.label(AnalysisFixtures.bd("80")).label())
                .isEqualTo("值得重点关注");
        assertThat(VerdictEngine.label(AnalysisFixtures.bd("65")).label())
                .isEqualTo("可分批关注");
        assertThat(VerdictEngine.label(AnalysisFixtures.bd("50")).label())
                .isEqualTo("观察等待");
        assertThat(VerdictEngine.label(AnalysisFixtures.bd("35")).label())
                .isEqualTo("偏谨慎");
        assertThat(VerdictEngine.label(AnalysisFixtures.bd("34.99")).label())
                .isEqualTo("回避");
    }

    @Test
    void healthyStockGetsReasonsAndRisksButNoWithheldReason() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        AnalysisResult result = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        assertThat(result.verdict().withheld()).isFalse();
        assertThat(result.verdict().score()).isNotNull();
        assertThat(result.verdict().score()).isBetween(
                result.verdict().scoreLow(), result.verdict().scoreHigh());
        assertThat(result.verdict().reasons()).isNotEmpty();
        assertThat(result.verdict().reasons()).allSatisfy(reason ->
                assertThat(reason.evidenceIds()).isNotEmpty());
    }

    @Test
    void stalePriceIsWithheldInsteadOfScored() {
        FactsSnapshot facts = AnalysisFixtures.stalePriceStock();
        AnalysisResult result = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        assertThat(result.verdict().withheld()).isTrue();
        assertThat(result.verdict().score()).isNull();
        assertThat(result.verdict().label()).isEqualTo("数据不足，暂不评级");
        assertThat(result.verdict().withheldReason()).contains("陈旧");
    }

    @Test
    void insufficientCoverageIsWithheld() {
        FactsSnapshot facts = AnalysisFixtures.emptyStock();
        AnalysisResult result = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        assertThat(result.verdict().withheld()).isTrue();
        assertThat(result.verdict().score()).isNull();
    }

    @Test
    void lowLiquidityCapsTheVerdict() {
        FactsSnapshot base = AnalysisFixtures.healthyStock();
        // 成交额 2000 万 < 5000 万。
        FactsSnapshot.Quote q = base.quote().data();
        FactsSnapshot lowAmount = new FactsSnapshot(base.inputCode(), base.instrument(),
                base.identity(),
                FactsSnapshot.Domain.ok(new FactsSnapshot.Quote(q.provider(), q.providerLabel(),
                        q.price(), q.pctChange(), AnalysisFixtures.bd("20000000"),
                        q.turnoverRate(), q.totalMarketCap(), null, null, null, q.asOf()),
                        q.asOf().toString()),
                base.fundProfile(),
                base.prices(), base.valuations(), base.indexValuation(), base.financials(),
                base.moneyflow(), base.indexProfile(), base.industry(), base.events(),
                false, 0, true, base.snapshotId(), base.snapshotAt());

        AnalysisResult result = InvestmentAnalysisService.analyze(lowAmount,
                AnalysisFixtures.TODAY);
        assertThat(result.score().hardFlags()).contains("LOW_LIQUIDITY");
        assertThat(result.verdict().score()).isLessThanOrEqualTo(BigDecimal.valueOf(55));
    }

    @Test
    void negativeNoticeReducesScoreAndAppearsInRisks() {
        FactsSnapshot base = AnalysisFixtures.healthyStock();
        FactsSnapshot facts = AnalysisFixtures.withEvent(base, "关于股东减持计划的公告",
                AnalysisFixtures.TODAY.minusDays(1));

        AnalysisResult result = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        assertThat(result.verdict().eventAdjustment()).isEqualByComparingTo("-1");
        assertThat(result.verdict().risks()).anySatisfy(reason ->
                assertThat(reason.text()).contains("减持"));
    }

    @Test
    void severeNegativeNoticeCapsTheVerdictAtCautious() {
        FactsSnapshot base = AnalysisFixtures.healthyStock();
        FactsSnapshot facts = AnalysisFixtures.withEvent(base, "关于公司被立案调查的公告",
                AnalysisFixtures.TODAY.minusDays(1));

        AnalysisResult result = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        assertThat(result.verdict().score()).isLessThanOrEqualTo(BigDecimal.valueOf(49));
        assertThat(result.verdict().label()).isIn("偏谨慎", "回避");
    }

    @Test
    void positiveNoticeAddsAtMostFivePoints() {
        FactsSnapshot base = AnalysisFixtures.healthyStock();
        FactsSnapshot facts = base;
        for (int i = 0; i < 8; i++) {
            facts = AnalysisFixtures.withEvent(facts, "关于回购股份的公告（" + i + "）",
                    AnalysisFixtures.TODAY.minusDays(1));
        }
        AnalysisResult result = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);
        assertThat(result.verdict().eventAdjustment()).isEqualByComparingTo("5");
    }

    @Test
    void ruleTextNeverUsesBannedPromotionalWords() {
        List<String> banned = List.of("必涨", "稳赚", "保证收益", "目标价", "建议满仓",
                "立即买入", "推荐股票", "上涨概率", "下跌概率");
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.healthyStock(), AnalysisFixtures.TODAY);
        StringBuilder text = new StringBuilder();
        for (Reason reason : result.verdict().reasons()) text.append(reason.text());
        for (Reason reason : result.verdict().risks()) text.append(reason.text());
        for (Scenario scenario : result.scenarios()) {
            text.append(scenario.condition()).append(scenario.narrative())
                    .append(scenario.invalidation());
        }
        for (String word : banned) {
            assertThat(text.toString()).doesNotContain(word);
        }
    }
}
