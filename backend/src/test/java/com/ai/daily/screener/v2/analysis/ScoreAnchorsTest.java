package com.ai.daily.screener.v2.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 锚点边界：差 / 中 / 好三档与两侧封顶，缺数据必须是 null 而不是 0。 */
class ScoreAnchorsTest {

    @Test
    void higherBetterHitsThreeAnchorsAndClamps() {
        assertThat(ScoreAnchors.higherBetter(AnalysisFixtures.bd("-15"), -15, 5, 25))
                .isEqualByComparingTo("0");
        assertThat(ScoreAnchors.higherBetter(AnalysisFixtures.bd("5"), -15, 5, 25))
                .isEqualByComparingTo("50");
        assertThat(ScoreAnchors.higherBetter(AnalysisFixtures.bd("25"), -15, 5, 25))
                .isEqualByComparingTo("100");
        assertThat(ScoreAnchors.higherBetter(AnalysisFixtures.bd("-100"), -15, 5, 25))
                .isEqualByComparingTo("0");
        assertThat(ScoreAnchors.higherBetter(AnalysisFixtures.bd("100"), -15, 5, 25))
                .isEqualByComparingTo("100");
        assertThat(ScoreAnchors.higherBetter(null, -15, 5, 25)).isNull();
    }

    @Test
    void lowerBetterIsInvertedAndToleratesReversedAnchors() {
        assertThat(ScoreAnchors.lowerBetter(AnalysisFixtures.bd("12"), 60, 30, 12))
                .isEqualByComparingTo("100");
        assertThat(ScoreAnchors.lowerBetter(AnalysisFixtures.bd("30"), 60, 30, 12))
                .isEqualByComparingTo("50");
        assertThat(ScoreAnchors.lowerBetter(AnalysisFixtures.bd("60"), 60, 30, 12))
                .isEqualByComparingTo("0");
        // 写反了也按同样语义计算，不把结论整体翻过来。
        assertThat(ScoreAnchors.lowerBetter(AnalysisFixtures.bd("30"), 12, 30, 60))
                .isEqualByComparingTo("50");
    }

    @Test
    void bandGivesFullScoreOnlyInsideTheBand() {
        assertThat(ScoreAnchors.band(AnalysisFixtures.bd("1"), 0, 1, 5, 10))
                .isEqualByComparingTo("100");
        assertThat(ScoreAnchors.band(AnalysisFixtures.bd("5"), 0, 1, 5, 10))
                .isEqualByComparingTo("100");
        assertThat(ScoreAnchors.band(AnalysisFixtures.bd("0.5"), 0, 1, 5, 10))
                .isEqualByComparingTo("50");
        assertThat(ScoreAnchors.band(AnalysisFixtures.bd("7.5"), 0, 1, 5, 10))
                .isEqualByComparingTo("50");
        assertThat(ScoreAnchors.band(AnalysisFixtures.bd("0"), 0, 1, 5, 10))
                .isEqualByComparingTo("0");
        assertThat(ScoreAnchors.band(AnalysisFixtures.bd("10"), 0, 1, 5, 10))
                .isEqualByComparingTo("0");
    }

    @Test
    void clampNeverLeavesTheZeroToOneHundredRange() {
        assertThat(ScoreAnchors.clamp(AnalysisFixtures.bd("-1")))
                .isEqualByComparingTo("0");
        assertThat(ScoreAnchors.clamp(AnalysisFixtures.bd("101")))
                .isEqualByComparingTo("100");
        assertThat(ScoreAnchors.clamp(AnalysisFixtures.bd("62.5")))
                .isEqualByComparingTo("62.5");
        assertThat(ScoreAnchors.clamp(null)).isNull();
    }
}
