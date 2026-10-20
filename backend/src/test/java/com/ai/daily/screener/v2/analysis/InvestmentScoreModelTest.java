package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六维评分：权重、缺失归一、覆盖率、分数区间、硬约束与行业特例。
 *
 * <p>重点不是「分数等于多少」，而是**缺数据不会变成 0 分**、
 * **分数永远落在覆盖率给出的区间里**、**硬约束不被其它维度的高分抵消**。
 */
class InvestmentScoreModelTest {

    @Test
    void healthyStockHasAllSixDimensionsAndAValidRange() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.healthyStock(),
                AnalysisFixtures.TODAY);

        assertThat(score.dimensions()).hasSize(6);
        assertThat(score.dimensions()).extracting(DimensionScore::key)
                .containsExactly("valuation", "growth", "quality", "health", "technical", "peers");
        assertThat(score.dimensions()).extracting(DimensionScore::weight)
                .containsExactly(22, 18, 18, 14, 20, 8);
        assertThat(score.finalScore()).isNotNull();
        assertThat(score.finalScore()).isBetween(score.scoreLow(), score.scoreHigh());
        assertThat(score.coverage()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(score.confidence()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(score.hardFlags()).doesNotContain("LOSS_MAKING", "ST_OR_DELISTING");
        // 同行数据没有接入：该维必须是 N/A，而不是 0 分。
        assertThat(score.dimension("peers").status()).isEqualTo("NA");
        assertThat(score.dimension("peers").score()).isNull();
    }

    @Test
    void sameSnapshotAndSameDayAlwaysProduceTheSameResult() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        ScoreResult first = InvestmentScoreModel.evaluate(facts, AnalysisFixtures.TODAY);
        ScoreResult second = InvestmentScoreModel.evaluate(facts, AnalysisFixtures.TODAY);
        assertThat(first).isEqualTo(second);
    }

    @Test
    void missingDataNeverBecomesZeroScore() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.emptyStock(),
                AnalysisFixtures.TODAY);

        assertThat(score.finalScore()).isNull();
        assertThat(score.dimensions()).allSatisfy(dimension ->
                assertThat(dimension.score()).isNull());
        assertThat(score.coverage()).isEqualByComparingTo("0");
    }

    @Test
    void lossMakingStockIsFlaggedAndCapped() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.lossStock(),
                AnalysisFixtures.TODAY);
        Verdict verdict = VerdictEngine.evaluate(AnalysisFixtures.lossStock(), score,
                AnalysisFixtures.TODAY);

        assertThat(score.hardFlags()).contains("LOSS_MAKING");
        assertThat(verdict.score()).isLessThanOrEqualTo(BigDecimal.valueOf(45));
        assertThat(verdict.label()).isIn("偏谨慎", "回避", "观察等待");
    }

    @Test
    void stStockCannotBeRaisedByOtherDimensions() {
        FactsSnapshot facts = AnalysisFixtures.stStock();
        ScoreResult score = InvestmentScoreModel.evaluate(facts, AnalysisFixtures.TODAY);
        Verdict verdict = VerdictEngine.evaluate(facts, score, AnalysisFixtures.TODAY);

        assertThat(score.hardFlags()).contains("ST_OR_DELISTING");
        assertThat(verdict.score()).isLessThanOrEqualTo(BigDecimal.valueOf(15));
        assertThat(verdict.label()).isEqualTo("回避");
    }

    @Test
    void financialSectorSkipsHealthAndReweightsValuation() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.bankStock(),
                AnalysisFixtures.TODAY);

        assertThat(score.sectorProfile()).isEqualTo("FINANCIAL");
        assertThat(score.dimension("health").status()).isEqualTo("NA");
        assertThat(score.dimension("health").score()).isNull();
        assertThat(score.dimension("quality").metrics()).hasSize(1);
        // PB 权重更高：估值维度仍有覆盖，不是 N/A。
        assertThat(score.dimension("valuation").available()).isTrue();
    }

    @Test
    void realEstateSkipsHealthAndPartOfQuality() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.realEstateStock(),
                AnalysisFixtures.TODAY);

        assertThat(score.sectorProfile()).isEqualTo("REAL_ESTATE");
        assertThat(score.dimension("health").status()).isEqualTo("NA");
        // 毛利率与 CFO 不适用，剩 ROE + 应收，权重重新归一。
        assertThat(score.dimension("quality").metrics())
                .extracting(com.ai.daily.screener.v2.analysis.ScoreMetric::key)
                .containsExactly("roe", "receivable_to_revenue");
        assertThat(score.dimension("quality").coverage()).isEqualByComparingTo("1");
    }

    @Test
    void newStockGetsShortHistoryFlagAndLowerConfidence() {
        FactsSnapshot facts = AnalysisFixtures.newStock();
        ScoreResult score = InvestmentScoreModel.evaluate(facts, AnalysisFixtures.TODAY);

        assertThat(score.hardFlags()).contains("SHORT_HISTORY");
        assertThat(score.confidence()).isLessThanOrEqualTo(BigDecimal.valueOf(0.6));
    }

    @Test
    void etfUsesIndexValuationTrendAndLiquidityWithoutPenalisingNaDimensions() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.etf(),
                AnalysisFixtures.TODAY);

        assertThat(score.assetType()).isEqualTo("ETF");
        assertThat(score.dimensions()).extracting(DimensionScore::key)
                .containsExactly("valuation", "trend", "liquidity", "size", "structure", "tracking");
        assertThat(score.dimension("size").status()).isEqualTo("NA");
        assertThat(score.dimension("tracking").status()).isEqualTo("NA");
        assertThat(score.dimension("valuation").available()).isTrue();
        assertThat(score.finalScore()).isNotNull();
    }

    @Test
    void etfSizeAndTrackingUseFundProfile() {
        ScoreResult score = InvestmentScoreModel.evaluate(
                AnalysisFixtures.etfWithFundProfile(), AnalysisFixtures.TODAY);

        DimensionScore size = score.dimension("size");
        assertThat(size.status()).isEqualTo("OK");
        assertThat(size.score()).isEqualByComparingTo("98.52");
        assertThat(size.metrics().get(0).basis()).contains("基金净资产");

        DimensionScore tracking = score.dimension("tracking");
        // 折溢价子项带权重但不可用：覆盖率如实是 0.6，而不是把缺项当满分。
        assertThat(tracking.status()).isEqualTo("PARTIAL");
        assertThat(tracking.coverage()).isEqualByComparingTo("0.6");
        assertThat(tracking.score()).isEqualByComparingTo("74.00");
        assertThat(tracking.metrics())
                .extracting(com.ai.daily.screener.v2.analysis.ScoreMetric::key)
                .containsExactly("etf_tracking_error", "etf_premium");
    }

    @Test
    void etfSizeFallsBackToEstimatedMarketCapWithExplicitBasis() {
        ScoreResult score = InvestmentScoreModel.evaluate(
                AnalysisFixtures.etfWithEstimatedMarketCap(), AnalysisFixtures.TODAY);

        DimensionScore size = score.dimension("size");
        assertThat(size.available()).isTrue();
        assertThat(size.metrics().get(0).value()).isEqualByComparingTo("100");
        assertThat(size.metrics().get(0).basis()).contains("不是基金净资产");
        // 没有档案就没有跟踪误差；折溢价不能单独撑起这一维。
        assertThat(score.dimension("tracking").status()).isEqualTo("NA");
    }

    @Test
    void indexUsesSixIndexDimensionsIncludingScale() {
        ScoreResult score = InvestmentScoreModel.evaluate(AnalysisFixtures.index(),
                AnalysisFixtures.TODAY);

        assertThat(score.assetType()).isEqualTo("INDEX");
        assertThat(score.dimensions()).extracting(DimensionScore::key)
                .containsExactly("valuation", "trend", "structure", "scale", "earnings", "industry");
        assertThat(score.dimension("scale").available()).isTrue();
        assertThat(score.dimension("earnings").status()).isEqualTo("NA");
    }
}
