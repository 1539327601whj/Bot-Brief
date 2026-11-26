package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 决策层的行为约束：
 * <ul>
 *   <li>数据足够时必须同时给出位置、时机、行动、长期/短期；</li>
 *   <li>数据不足时四块都必须是 {@code INSUFFICIENT}，不能用「中性」或 0 分顶替；</li>
 *   <li>低位 + 趋势不弱 → 可分批关注；低位 + 趋势向下 → 等待企稳；</li>
 *   <li>行动标签只能来自封闭集合。</li>
 * </ul>
 */
class DecisionEngineTest {

    @Test
    void healthyStockGetsAllFourAnswers() {
        FactsSnapshot facts = AnalysisFixtures.healthyStock();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        Decision decision = DecisionEngine.evaluate(facts, analysis.score(),
                analysis.verdict(), analysis.buyZone());

        assertThat(decision.position().axes()).hasSize(3);
        assertThat(decision.position().key()).isIn("LOW", "NEUTRAL", "HIGH");
        assertThat(decision.timing().key()).isNotEqualTo("INSUFFICIENT");
        assertThat(decision.action().key()).isIn(
                "BUILD_GRADUALLY", "HOLD_OBSERVE", "WAIT", "AVOID");
        assertThat(decision.horizon().longScore()).isNotNull();
        assertThat(decision.horizon().shortScore()).isNotNull();
        assertThat(decision.summary()).contains("位置：", "时机：", "行动：", "周期：");
        assertThat(decision.watchItems()).isNotEmpty();
        assertThat(decision.disclaimer()).isEqualTo(Decision.DISCLAIMER);
    }

    @Test
    void emptyStockIsInsufficientEverywhere() {
        FactsSnapshot facts = AnalysisFixtures.emptyStock();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        Decision decision = DecisionEngine.evaluate(facts, analysis.score(),
                analysis.verdict(), analysis.buyZone());

        assertThat(decision.position().key()).isEqualTo("INSUFFICIENT");
        assertThat(decision.timing().key()).isEqualTo("INSUFFICIENT");
        assertThat(decision.action().key()).isEqualTo("INSUFFICIENT");
        assertThat(decision.horizon().key()).isEqualTo("INSUFFICIENT");
        assertThat(decision.horizon().longScore()).isNull();
        assertThat(decision.horizon().shortScore()).isNull();
        assertThat(decision.action().label()).isEqualTo("数据不足");
    }

    @Test
    void lowValuationWithStabilizingPriceIsLowPositionAndCanAccumulate() {
        FactsSnapshot facts = lowValuationStabilizing();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        Decision decision = DecisionEngine.evaluate(facts, analysis.score(),
                analysis.verdict(), analysis.buyZone());

        assertThat(decision.position().key()).isEqualTo("LOW");
        assertThat(decision.position().label()).isEqualTo("相对低位");
        assertThat(decision.timing().key()).isEqualTo("ACCUMULATE");
        assertThat(decision.action().key()).isEqualTo("BUILD_GRADUALLY");
        assertThat(decision.action().tranchePlan()).isNotEmpty();
    }

    @Test
    void fallingPriceWithLowValuationWaitsForStabilization() {
        FactsSnapshot facts = lowValuationFalling();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        Decision decision = DecisionEngine.evaluate(facts, analysis.score(),
                analysis.verdict(), analysis.buyZone());

        assertThat(decision.position().key()).isIn("LOW", "NEUTRAL");
        assertThat(decision.timing().key()).isEqualTo("WAIT_STABILIZE");
        assertThat(decision.action().key()).isEqualTo("WAIT");
    }

    @Test
    void withheldVerdictStillAnswersTimingFromPriceAndPosition() {
        // 六维覆盖率不足只影响「评级」，不影响「位置 + 趋势」这两个轴。
        // 以前这里会把时机/行动一起打成数据不足，页面看上去像什么都不知道。
        FactsSnapshot facts = lowValuationStabilizing();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        Decision decision = DecisionEngine.evaluate(facts, analysis.score(),
                withheldFrom(analysis.verdict(), List.of()), analysis.buyZone());

        assertThat(decision.position().key()).isNotEqualTo("INSUFFICIENT");
        assertThat(decision.timing().key()).isNotEqualTo("INSUFFICIENT");
        assertThat(decision.action().key()).isNotEqualTo("INSUFFICIENT");
        assertThat(decision.timing().reasons()).extracting(Reason::text)
                .anyMatch(t -> t.contains("六维评级暂缓"));
        assertThat(decision.action().tranchePlan())
                .anyMatch(p -> p.contains("不是加仓依据"));
        assertThat(decision.risks()).extracting(Reason::text)
                .anyMatch(t -> t.contains("六维评级暂缓"));
    }

    @Test
    void stalePriceStillBlocksTimingEvenWhenAxesAreAvailable() {
        // 位置、趋势都是拿旧价格算的，这时给「可分批关注」等于用过期行情指挥仓位。
        FactsSnapshot facts = lowValuationStabilizing();
        AnalysisResult analysis = InvestmentAnalysisService.analyze(facts, AnalysisFixtures.TODAY);

        Decision decision = DecisionEngine.evaluate(facts, analysis.score(),
                withheldFrom(analysis.verdict(), List.of("PRICE_STALE")), analysis.buyZone());

        assertThat(decision.timing().key()).isEqualTo("INSUFFICIENT");
        assertThat(decision.action().key()).isEqualTo("INSUFFICIENT");
        assertThat(decision.timing().reasons()).extracting(Reason::text)
                .anyMatch(t -> t.contains("价格数据陈旧"));
    }

    private static Verdict withheldFrom(Verdict base, List<String> hardFlags) {
        return new Verdict("数据不足，暂不评级", "INSUFFICIENT", null, null, null,
                base.coverage(), base.confidence(), base.eventAdjustment(),
                hardFlags, List.of(), base.risks(), "六维覆盖率不足 50%");
    }

    // ------------------------------------------------------------------
    // 固定快照：只替换价格与估值，财务/身份沿用 healthyStock
    // ------------------------------------------------------------------

    private static FactsSnapshot lowValuationStabilizing() {
        FactsSnapshot base = AnalysisFixtures.healthyStock();
        List<WarehouseRows.PriceBar> prices = new ArrayList<>();
        LocalDate start = AnalysisFixtures.PRICE_AS_OF.minusDays(400);
        for (int i = 0; i < 300; i++) {
            BigDecimal close = i < 200
                    ? BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(i).multiply(
                    BigDecimal.valueOf(0.3)))
                    : BigDecimal.valueOf(40);
            prices.add(bar(start.plusDays(i), close));
        }
        return withPricesAndLowValuation(base, prices, BigDecimal.valueOf(40));
    }

    private static FactsSnapshot lowValuationFalling() {
        FactsSnapshot base = AnalysisFixtures.healthyStock();
        List<WarehouseRows.PriceBar> prices = new ArrayList<>();
        LocalDate start = AnalysisFixtures.PRICE_AS_OF.minusDays(400);
        for (int i = 0; i < 300; i++) {
            BigDecimal close = BigDecimal.valueOf(100).subtract(BigDecimal.valueOf(i).multiply(
                    BigDecimal.valueOf(0.2)));
            prices.add(bar(start.plusDays(i), close));
        }
        return withPricesAndLowValuation(base, prices, BigDecimal.valueOf(40));
    }

    private static WarehouseRows.PriceBar bar(LocalDate date, BigDecimal close) {
        return new WarehouseRows.PriceBar("600519", date, "QFQ",
                close, close, close, close, BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(200_000_000), BigDecimal.valueOf(2),
                BigDecimal.ZERO, "eastmoney", LocalDateTime.of(2026, 10, 16, 18, 0));
    }

    private static FactsSnapshot withPricesAndLowValuation(FactsSnapshot base,
                                                           List<WarehouseRows.PriceBar> prices,
                                                           BigDecimal price) {
        List<WarehouseRows.ValuationPoint> valuations = new ArrayList<>();
        int count = 946;
        for (int i = 0; i < count; i++) {
            BigDecimal pe = BigDecimal.valueOf(10)
                    .add(BigDecimal.valueOf(i % 100).multiply(BigDecimal.valueOf(0.1)));
            if (i == count - 1) {
                pe = BigDecimal.valueOf(10.1);
            }
            BigDecimal pb = BigDecimal.valueOf(1)
                    .add(BigDecimal.valueOf(i % 100).multiply(BigDecimal.valueOf(0.02)));
            valuations.add(new WarehouseRows.ValuationPoint("600519",
                    AnalysisFixtures.PRICE_AS_OF.minusDays(2L * (count - 1 - i)),
                    pe, pb, null, null, price, null, "eastmoney-valueanalysis",
                    LocalDateTime.of(2026, 10, 16, 18, 0)));
        }
        FactsSnapshot.Quote old = base.quote().data();
        FactsSnapshot.Quote quote = new FactsSnapshot.Quote(old.provider(), old.providerLabel(),
                price, BigDecimal.ZERO, old.amount(), old.turnoverRate(), old.totalMarketCap(),
                old.peTtm(), old.pb(), old.dividendYield(), old.asOf());
        return new FactsSnapshot(base.inputCode(), base.instrument(), base.identity(),
                FactsSnapshot.Domain.ok(quote, AnalysisFixtures.PRICE_AS_OF.toString()),
                base.fundProfile(),
                FactsSnapshot.Domain.ok(prices, AnalysisFixtures.PRICE_AS_OF.toString()),
                FactsSnapshot.Domain.ok(valuations, AnalysisFixtures.PRICE_AS_OF.toString()),
                base.indexValuation(), base.financials(), base.moneyflow(),
                base.indexProfile(), base.industry(), base.events(),
                base.syncPending(), base.retryAfterMs(), base.fromCache(),
                base.snapshotId(), base.snapshotAt(), base.etfResearch());
    }
}
