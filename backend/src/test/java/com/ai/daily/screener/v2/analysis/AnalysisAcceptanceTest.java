package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章程验收：十一类标的过同一条流水线，不因为类型/缺数据出现「凭空数字」。
 *
 * <p>这里断言的是**不变量**，不是具体分数：分数必须落在自己给出的区间里、
 * 缺数据必须不给数字、止损必须低于区间下沿、情景卡不得出现概率或目标价。
 */
class AnalysisAcceptanceTest {

    private static final Set<String> LABELS = Set.of(
            "值得重点关注", "可分批关注", "观察等待", "偏谨慎", "回避", "数据不足，暂不评级");
    private static final Set<String> BUY_ZONE_STATUS = Set.of(
            "IN_ZONE", "ABOVE_ZONE", "BELOW_ZONE", "BROKEN", "WITHHELD");
    private static final List<String> BANNED = List.of(
            "必涨", "稳赚", "保证收益", "目标价", "建议满仓", "立即买入", "推荐股票");

    @Test
    void allElevenFixtureClassesObeyTheRules() {
        List<FactsSnapshot> fixtures = List.of(
                AnalysisFixtures.healthyStock(),
                AnalysisFixtures.lossStock(),
                AnalysisFixtures.stStock(),
                AnalysisFixtures.bankStock(),
                AnalysisFixtures.realEstateStock(),
                AnalysisFixtures.newStock(),
                AnalysisFixtures.stalePriceStock(),
                AnalysisFixtures.noValuationStock(),
                AnalysisFixtures.emptyStock(),
                AnalysisFixtures.etf(),
                AnalysisFixtures.index());

        for (FactsSnapshot facts : fixtures) {
            AnalysisResult result = InvestmentAnalysisService.analyze(
                    facts, AnalysisFixtures.TODAY);
            String where = facts.instrument().key();

            assertThat(result.rulesVersion()).isEqualTo("code-lookup-v2-p5");
            assertThat(result.score()).as(where).isNotNull();
            assertThat(result.verdict()).as(where).isNotNull();
            assertThat(result.buyZone()).as(where).isNotNull();
            assertThat(result.score().dimensions()).as(where).hasSize(6);

            assertScoreRange(result.score(), where);
            assertVerdict(result, where);
            assertBuyZone(result.buyZone(), where, facts.instrument().assetType());
            assertScenarios(result, where);
        }
    }

    private static void assertScoreRange(ScoreResult score, String where) {
        assertThat(score.coverage()).as(where).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(score.confidence()).as(where).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        if (score.finalScore() == null) {
            // 一个维度都算不出来时，连区间也不给——缺数据不伪造成 0 分。
            assertThat(score.scoreLow()).as(where).isNull();
            assertThat(score.scoreHigh()).as(where).isNull();
            return;
        }
        assertThat(score.scoreLow()).as(where).isNotNull();
        assertThat(score.scoreHigh()).as(where).isNotNull();
        assertThat(score.scoreLow()).as(where).isLessThanOrEqualTo(score.scoreHigh());
        assertThat(score.finalScore()).as(where)
                .isBetween(score.scoreLow(), score.scoreHigh());
    }

    private static void assertVerdict(AnalysisResult result, String where) {
        Verdict verdict = result.verdict();
        assertThat(LABELS).as(where).contains(verdict.label());
        assertThat(verdict.score() == null ? BigDecimal.ZERO : verdict.score())
                .as(where).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
        for (Reason reason : verdict.reasons()) {
            assertThat(reason.text()).as(where).isNotBlank();
        }
        for (Reason risk : verdict.risks()) {
            assertThat(risk.text()).as(where).isNotBlank();
        }
        String text = verdict.reasons().stream().map(Reason::text)
                .reduce("", String::concat)
                + verdict.risks().stream().map(Reason::text).reduce("", String::concat);
        for (String word : BANNED) {
            assertThat(text).as(where).doesNotContain(word);
        }
        if (verdict.withheld()) {
            assertThat(verdict.score()).as(where).isNull();
            assertThat(verdict.label()).as(where).isEqualTo("数据不足，暂不评级");
            assertThat(verdict.withheldReason()).as(where).isNotBlank();
        }
    }

    private static void assertBuyZone(BuyZone zone, String where, String assetType) {
        assertThat(BUY_ZONE_STATUS).as(where).contains(zone.status());
        if ("WITHHELD".equals(zone.status())) {
            assertThat(zone.low()).as(where).isNull();
            assertThat(zone.high()).as(where).isNull();
            assertThat(zone.stopLoss()).as(where).isNull();
            assertThat(zone.notes()).as(where).isNotEmpty();
            return;
        }
        if ("BROKEN".equals(zone.status())) {
            assertThat(zone.stopLoss()).as(where).isNotNull();
            return;
        }
        assertThat(zone.low()).as(where).isPositive();
        assertThat(zone.high()).as(where).isGreaterThan(zone.low());
        assertThat(zone.stopLoss()).as(where)
                .isNotNull()
                .isLessThan(zone.low());
        assertThat(zone.riskPct()).as(where).isNotNull();
        BigDecimal limit = "STOCK".equals(assetType)
                ? BigDecimal.valueOf(18) : BigDecimal.valueOf(12);
        assertThat(zone.riskPct()).as(where)
                .isBetween(BigDecimal.ZERO, limit);
    }

    private static void assertScenarios(AnalysisResult result, String where) {
        if (result.verdict().withheld()) {
            assertThat(result.scenarios()).as(where).isEmpty();
            return;
        }
        assertThat(result.scenarios()).as(where).hasSize(3);
        assertThat(result.scenarios()).extracting(Scenario::key)
                .as(where).containsExactly("bull", "base", "bear");
        assertThat(result.scenarios().stream().filter(Scenario::triggered).count())
                .as(where).isEqualTo(1);
        for (Scenario scenario : result.scenarios()) {
            assertThat(scenario.evidenceIds()).as(where).isNotEmpty();
            assertThat(scenario.disclaimer()).as(where).isEqualTo(Scenario.DISCLAIMER);
            String text = scenario.condition() + scenario.narrative() + scenario.invalidation();
            for (String word : BANNED) {
                assertThat(text).as(where).doesNotContain(word);
            }
        }
    }
}
