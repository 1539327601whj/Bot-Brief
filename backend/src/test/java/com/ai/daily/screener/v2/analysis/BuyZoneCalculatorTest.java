package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参考区间：交集、桥接、过宽、止损距离、估值锚回退与必须放弃的场景。
 *
 * <p>断言的是**关系**（下沿 < 上沿、止损在下沿之下、风险百分比不越上限），
 * 不是某一串固定数字——固定数字会在调锚点时全红，但关系不该变。
 */
class BuyZoneCalculatorTest {

    @Test
    void healthyStockGetsAZoneAndAStopBelowIt() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.healthyStock(), AnalysisFixtures.TODAY);
        BuyZone zone = result.buyZone();

        assertThat(zone.status()).isIn("IN_ZONE", "ABOVE_ZONE", "BELOW_ZONE");
        assertThat(zone.low()).isNotNull();
        assertThat(zone.high()).isNotNull();
        assertThat(zone.low()).isLessThan(zone.high());
        assertThat(zone.stopLoss()).isLessThan(zone.low());
        assertThat(zone.riskPct()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(18));
        assertThat(zone.basis()).isNotEmpty();
        assertThat(String.join(" ", zone.notes())).contains("参考");
    }

    @Test
    void negativePeFallsBackToPbAnchor() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.lossStock(), AnalysisFixtures.TODAY);
        BuyZone zone = result.buyZone();

        assertThat(zone.status()).isNotEqualTo("WITHHELD");
        assertThat(zone.basis()).anySatisfy(reason ->
                assertThat(reason.text()).contains("PB"));
    }

    @Test
    void missingValuationHistoryWithholdsTheZone() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.noValuationStock(), AnalysisFixtures.TODAY);

        assertThat(result.buyZone().status()).isEqualTo("WITHHELD");
        assertThat(result.buyZone().low()).isNull();
        assertThat(result.buyZone().notes()).anySatisfy(note ->
                assertThat(note).contains("估值锚"));
    }

    @Test
    void stStockAndShortHistoryDoNotGetAZone() {
        BuyZone st = InvestmentAnalysisService.analyze(
                AnalysisFixtures.stStock(), AnalysisFixtures.TODAY).buyZone();
        assertThat(st.status()).isEqualTo("WITHHELD");

        BuyZone fresh = InvestmentAnalysisService.analyze(
                AnalysisFixtures.newStock(), AnalysisFixtures.TODAY).buyZone();
        assertThat(fresh.status()).isEqualTo("WITHHELD");
        assertThat(fresh.notes()).anySatisfy(note ->
                assertThat(note).contains("上市不足"));
    }

    @Test
    void stalePriceWithholdsTheZone() {
        BuyZone zone = InvestmentAnalysisService.analyze(
                AnalysisFixtures.stalePriceStock(), AnalysisFixtures.TODAY).buyZone();

        assertThat(zone.status()).isEqualTo("WITHHELD");
        assertThat(zone.notes()).anySatisfy(note ->
                assertThat(note).contains("3 个交易日"));
    }

    @Test
    void etfZoneUsesIndexValuationAndItsOwnBars() {
        AnalysisResult result = InvestmentAnalysisService.analyze(
                AnalysisFixtures.etf(), AnalysisFixtures.TODAY);
        BuyZone zone = result.buyZone();

        assertThat(zone.status()).isNotEqualTo("WITHHELD");
        assertThat(zone.low()).isNotNull();
        assertThat(zone.high()).isNotNull();
        assertThat(zone.stopLoss()).isLessThan(zone.low());
        assertThat(zone.riskPct()).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(12));
        assertThat(zone.basis()).anySatisfy(reason ->
                assertThat(reason.text()).contains("指数"));
    }

    @Test
    void indexZoneRoundsToTwoDecimals() {
        BuyZone zone = InvestmentAnalysisService.analyze(
                AnalysisFixtures.index(), AnalysisFixtures.TODAY).buyZone();

        assertThat(zone.status()).isNotEqualTo("WITHHELD");
        assertThat(zone.low().scale()).isLessThanOrEqualTo(2);
        assertThat(zone.high().scale()).isLessThanOrEqualTo(2);
        assertThat(zone.stopLoss().scale()).isLessThanOrEqualTo(2);
    }

    @Test
    void zoneIsDeterministicForTheSameSnapshot() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        BuyZone first = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY).buyZone();
        BuyZone second = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY).buyZone();
        assertThat(first).isEqualTo(second);
    }
}
