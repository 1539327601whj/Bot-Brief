package com.ai.daily.screener.v2;

import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技术摘要的口径。
 *
 * <p>这些数字会直接变成「支撑位」「参考买入区间」，所以每条断言都钉住
 * **用的是哪一段窗口、缺数据时是 null 还是 0**，而不是只断言「算出来了」。
 */
class TechnicalCalculatorTest {

    private static WarehouseRows.PriceBar bar(LocalDate date, String close, String high,
                                              String low) {
        return new WarehouseRows.PriceBar("STOCK:600519", date, "QFQ",
                new BigDecimal(close), high == null ? null : new BigDecimal(high),
                low == null ? null : new BigDecimal(low), new BigDecimal(close),
                new BigDecimal("1000"), new BigDecimal("1000000"), new BigDecimal("0.5"),
                new BigDecimal("0.1"), "test", null);
    }

    /** 连续上涨 300 天：均线应依次抬高，动量与一年分位为正。 */
    @Test
    void risingSeriesProducesOrderedAveragesAndPositiveMomentum() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2025, 7, 1);
        BigDecimal step = new BigDecimal("0.1");
        BigDecimal half = new BigDecimal("0.2");
        for (int i = 0; i < 300; i++) {
            BigDecimal close = new BigDecimal("10").add(step.multiply(BigDecimal.valueOf(i)));
            bars.add(bar(start.plusDays(i), close.toPlainString(),
                    close.add(half).toPlainString(), close.subtract(half).toPlainString()));
        }

        TechnicalCalculator.TechnicalSummary t = TechnicalCalculator.compute(bars);

        assertThat(t.barCount()).isEqualTo(300);
        assertThat(t.lastDate()).isEqualTo(start.plusDays(299));
        assertThat(t.price()).isEqualByComparingTo("39.9");
        assertThat(t.hasOhlc()).isTrue();
        assertThat(t.ma20()).isGreaterThan(t.ma60());
        assertThat(t.ma60()).isGreaterThan(t.ma120());
        assertThat(t.ma250()).isNotNull();
        assertThat(t.atr14()).isPositive();
        assertThat(t.low20()).isEqualByComparingTo("37.8");
        assertThat(t.high1y()).isNotNull();
        assertThat(t.pricePercentile1y()).isEqualByComparingTo("100.00");
        assertThat(t.momentum20d()).isPositive();
        assertThat(t.momentum60d()).isPositive();
    }

    /**
     * 只有收盘价（腾讯兜底日线）时：均线照算，ATR 退化成收盘价差，但 hasOhlc=false。
     * 支撑位算法必须知道这一点，不能假装有高低价。
     */
    @Test
    void closeOnlySeriesStillComputesAveragesButFlagsMissingOhlc() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 80; i++) {
            bars.add(bar(start.plusDays(i), String.valueOf(20 + i * 0.05), null, null));
        }

        TechnicalCalculator.TechnicalSummary t = TechnicalCalculator.compute(bars);

        assertThat(t.hasOhlc()).isFalse();
        assertThat(t.ma20()).isNotNull();
        assertThat(t.ma60()).isNotNull();
        assertThat(t.ma120()).isNull();
        assertThat(t.ma250()).isNull();
        assertThat(t.atr14()).isNotNull();
        // 窗口不足时返回 null，不拿更短窗口顶替。
        assertThat(t.momentum60d()).isNotNull();
        assertThat(t.pricePercentile1y()).isEqualByComparingTo("100.00");
    }

    @Test
    void emptyOrInvalidBarsYieldAllNullRatherThanZero() {
        TechnicalCalculator.TechnicalSummary empty = TechnicalCalculator.compute(List.of());
        assertThat(empty.barCount()).isZero();
        assertThat(empty.price()).isNull();
        assertThat(empty.ma20()).isNull();

        List<WarehouseRows.PriceBar> invalid = List.of(
                bar(LocalDate.of(2026, 1, 1), "0", "1", "0.5"),
                new WarehouseRows.PriceBar("STOCK:600519", null, "QFQ", null, null, null,
                        new BigDecimal("10"), null, null, null, null, "test", null));
        TechnicalCalculator.TechnicalSummary t = TechnicalCalculator.compute(invalid);
        assertThat(t.barCount()).isZero();
        assertThat(t.price()).isNull();
    }

    @Test
    void percentileCountsNotHigherBarsAndMomentumSkipsMissingHistory() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 10; i++) {
            bars.add(bar(start.plusDays(i), String.valueOf(10 + i), null, null));
        }
        // 最后一根是 19，前 9 根都比它低 → 10/10。
        assertThat(TechnicalCalculator.percentileInWindow(bars, 250,
                new BigDecimal("19"))).isEqualByComparingTo("100.00");
        // 第 6 根是 15：<=15 的有 6 根 → 60%。
        assertThat(TechnicalCalculator.percentileInWindow(bars, 250,
                new BigDecimal("15"))).isEqualByComparingTo("60.00");
        // 只有 10 根，20 日动量没有足够历史 → null，不拿 9 根顶替。
        assertThat(TechnicalCalculator.momentum(bars, 20)).isNull();
        assertThat(TechnicalCalculator.momentum(bars, 5)).isEqualByComparingTo("35.71");
    }
}
