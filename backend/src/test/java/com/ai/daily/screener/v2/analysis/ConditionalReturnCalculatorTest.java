package com.ai.daily.screener.v2.analysis;

import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalReturnCalculatorTest {

    private static List<WarehouseRows.PriceBar> bars(int n, double start, double step) {
        List<WarehouseRows.PriceBar> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(bar(LocalDate.of(2010, 1, 1).plusDays(i), start + i * step));
        }
        return out;
    }

    private static WarehouseRows.PriceBar bar(LocalDate date, double close) {
        return new WarehouseRows.PriceBar("600000", date, "qfq",
                null, null, null, BigDecimal.valueOf(close), null, null, null, null, "test", null);
    }

    @Test
    void withoutCurrentPercentileItSaysWhyInsteadOfGuessing() {
        ConditionalReturnCalculator.Outlook o =
                ConditionalReturnCalculator.compute(bars(2600, 10, 0.01), null);
        assertThat(o.available()).isFalse();
        assertThat(o.horizons()).isEmpty();
        assertThat(o.status()).contains("价格分位");
    }

    @Test
    void flatSeriesHasNoPercentileAndNoSamples() {
        ConditionalReturnCalculator.Outlook o =
                ConditionalReturnCalculator.compute(bars(2600, 10, 0), new BigDecimal("50"));
        assertThat(o.available()).isFalse();
        assertThat(o.status()).contains("样本不足");
    }

    @Test
    void uptrendProducesOneAndThreeYearStatsWithFullWinRate() {
        ConditionalReturnCalculator.Outlook o =
                ConditionalReturnCalculator.compute(bars(2600, 10, 0.01), new BigDecimal("100"));

        assertThat(o.available()).isTrue();
        assertThat(o.currentPercentile()).isEqualByComparingTo("100.0");
        assertThat(o.horizons()).extracting(ConditionalReturnCalculator.HorizonStats::key)
                .containsExactly("1y", "3y");
        for (ConditionalReturnCalculator.HorizonStats h : o.horizons()) {
            assertThat(h.sampleCount()).isGreaterThan(20);
            assertThat(h.winRatePct()).isEqualByComparingTo("100.0");
            assertThat(h.medianReturnPct()).isPositive();
            assertThat(h.p25ReturnPct()).isLessThanOrEqualTo(h.medianReturnPct());
            assertThat(h.p75ReturnPct()).isGreaterThanOrEqualTo(h.medianReturnPct());
        }
        assertThat(o.note()).contains("不预测");
    }

    @Test
    void tooLittleHistoryReturnsAReasonInsteadOfAFakeDistribution() {
        // 只有一年多日线：一年持有期都凑不出完整样本
        ConditionalReturnCalculator.Outlook o =
                ConditionalReturnCalculator.compute(bars(400, 10, 0.01), new BigDecimal("100"));
        assertThat(o.available()).isFalse();
        assertThat(o.status()).contains("样本不足");
    }

    @Test
    void inputOrderAndBadRowsDoNotChangeTheResult() {
        List<WarehouseRows.PriceBar> ordered = bars(2600, 10, 0.01);
        List<WarehouseRows.PriceBar> shuffled = new ArrayList<>(ordered);
        java.util.Collections.reverse(shuffled);
        shuffled.add(bar(LocalDate.of(2010, 1, 1).minusDays(1), -5));

        ConditionalReturnCalculator.Outlook a =
                ConditionalReturnCalculator.compute(ordered, new BigDecimal("100"));
        ConditionalReturnCalculator.Outlook b =
                ConditionalReturnCalculator.compute(shuffled, new BigDecimal("100"));

        assertThat(b.horizons()).isEqualTo(a.horizons());
    }
}
