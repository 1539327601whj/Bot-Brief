package com.ai.daily.screener;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricePositionCalculatorTest {

    private static final LocalDate START = LocalDate.of(2025, 10, 1);

    /** 线性走势的日线：从 from 均匀走到 to，便于手算期望值。 */
    private static List<PricePositionCalculator.Bar> ramp(double from, double to, int bars) {
        List<PricePositionCalculator.Bar> out = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double v = from + (to - from) * i / (bars - 1.0);
            out.add(new PricePositionCalculator.Bar(START.plusDays(i), BigDecimal.valueOf(v)));
        }
        return out;
    }

    private static List<PricePositionCalculator.Bar> flat(double v, int bars) {
        List<PricePositionCalculator.Bar> out = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            out.add(new PricePositionCalculator.Bar(START.plusDays(i), BigDecimal.valueOf(v)));
        }
        return out;
    }

    @Test
    void emptyBarsYieldUnavailableInsteadOfFakeNeutralNumbers() {
        PricePosition p = PricePositionCalculator.compute(List.of());
        assertThat(p.isAvailable()).isFalse();
        assertThat(p.getPricePercentile()).isNull();
        assertThat(p.getDrawdownFromHigh()).isNull();
        assertThat(p.getDegradations()).isNotEmpty();
    }

    @Test
    void barAtTheTopOfTheYearHasZeroDrawdownAndFullPercentile() {
        PricePosition p = PricePositionCalculator.compute(ramp(10, 20, 250));
        assertThat(p.isAvailable()).isTrue();
        assertThat(p.getPricePercentile()).isEqualByComparingTo("100.00");
        assertThat(p.getDrawdownFromHigh()).isEqualByComparingTo("0.0000");
        assertThat(p.getBarCount()).isEqualTo(250);
    }

    @Test
    void barAtTheBottomOfTheYearHasLargeDrawdownAndZeroPercentile() {
        PricePosition p = PricePositionCalculator.compute(ramp(20, 10, 250));
        assertThat(p.getPricePercentile()).isEqualByComparingTo("0.00");
        assertThat(p.getDrawdownFromHigh()).isEqualByComparingTo("50.0000");
        assertThat(p.getMaxDrawdownInYear()).isEqualByComparingTo("50.00");
    }

    @Test
    void drawdownIsMeasuredAgainstTheHighestCloseNotTheAverage() {
        List<PricePositionCalculator.Bar> bars = new ArrayList<>(ramp(10, 30, 100));
        bars.add(new PricePositionCalculator.Bar(START.plusDays(100), BigDecimal.valueOf(21)));
        PricePosition p = PricePositionCalculator.compute(bars);
        assertThat(p.getDrawdownFromHigh()).isEqualByComparingTo("30.0000");
    }

    @Test
    void flatSeriesGetsNeutralPercentileAndSaysSo() {
        PricePosition p = PricePositionCalculator.compute(flat(15, 250));
        assertThat(p.getPricePercentile()).isEqualByComparingTo("50.00");
        assertThat(p.getDegradations()).anyMatch(d -> d.contains("无波动区间"));
    }

    @Test
    void movingAverageRelationsAreSignedByDirectionNotMagnitude() {
        PricePosition up = PricePositionCalculator.compute(ramp(10, 20, 250));
        assertThat(up.getVsMa20()).isPositive();
        assertThat(up.getVsMa60()).isPositive();
        assertThat(up.getVsMa250()).isPositive();

        PricePosition down = PricePositionCalculator.compute(ramp(20, 10, 250));
        assertThat(down.getVsMa20()).isNegative();
        assertThat(down.getVsMa250()).isNegative();
    }

    @Test
    void shortSeriesCannotConfirmMa250ButStillGivesPercentile() {
        PricePosition p = PricePositionCalculator.compute(ramp(10, 18, 100));
        assertThat(p.isAvailable()).isTrue();
        assertThat(p.getVsMa250()).isNull();
        assertThat(p.getVsMa60()).isNotNull();
        assertThat(p.getDegradations()).anyMatch(d -> d.contains("MA250"));
    }

    @Test
    void volatilityIsNullBelowTheSampleFloorAndPositiveAboveIt() {
        PricePosition tooShort = PricePositionCalculator.compute(ramp(10, 12, 30));
        assertThat(tooShort.getAnnualizedVolatility()).isNull();
        assertThat(tooShort.getDegradations()).anyMatch(d -> d.contains("年化波动"));

        PricePosition enough = PricePositionCalculator.compute(ramp(10, 20, 250));
        assertThat(enough.getAnnualizedVolatility()).isNotNull();
        assertThat(enough.getAnnualizedVolatility()).isPositive();
    }

    @Test
    void volatileSeriesHasHigherVolatilityThanSmoothOne() {
        double smooth = PricePositionCalculator.compute(ramp(10, 20, 250)).getAnnualizedVolatility().doubleValue();

        List<PricePositionCalculator.Bar> jagged = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            double v = 15 + (i % 2 == 0 ? 3 : -3);
            jagged.add(new PricePositionCalculator.Bar(START.plusDays(i), BigDecimal.valueOf(v)));
        }
        double choppy = PricePositionCalculator.compute(jagged).getAnnualizedVolatility().doubleValue();
        assertThat(choppy).isGreaterThan(smooth);
    }

    @Test
    void onlyTheLastYearIsUsedForTheWindow() {
        // 一年前有一根极高的价格，落在 250 根窗口之外，不该影响回撤
        List<PricePositionCalculator.Bar> bars = new ArrayList<>();
        bars.add(new PricePositionCalculator.Bar(START, BigDecimal.valueOf(100)));
        bars.addAll(ramp(20, 10, 250));
        PricePosition p = PricePositionCalculator.compute(bars);
        assertThat(p.getBarCount()).isEqualTo(251);
        assertThat(p.getDrawdownFromHigh()).isEqualByComparingTo("50.0000");
    }

    @Test
    void staleDetectionUsesFifteenCalendarDays() {
        PricePosition p = PricePositionCalculator.compute(ramp(10, 20, 250));
        LocalDate last = p.getLastTradeDate();
        assertThat(PricePositionCalculator.isStale(p, last.plusDays(15))).isFalse();
        assertThat(PricePositionCalculator.isStale(p, last.plusDays(16))).isTrue();
        assertThat(PricePositionCalculator.isStale(PricePosition.unavailable("无日线"), last)).isTrue();
    }

    @Test
    void rowsWithNonPositiveClosesAreIgnoredNotTreatedAsCheap() {
        List<PricePositionCalculator.Bar> bars = new ArrayList<>(ramp(10, 20, 100));
        bars.add(new PricePositionCalculator.Bar(START.plusDays(100), BigDecimal.ZERO));
        PricePosition p = PricePositionCalculator.compute(bars);
        // 0 收盘价不是「便宜」，是坏数据，直接丢掉
        assertThat(p.getBarCount()).isEqualTo(100);
    }

    @Test
    void fiveYearCagrUsesCalendarSpanAndNeedsEnoughCoverage() {
        LocalDate start = LocalDate.of(2020, 1, 1);
        List<PricePositionCalculator.Bar> bars = new ArrayList<>();
        for (int i = 0; i < 1300; i++) {
            // 1300 个交易日约 5.2 个自然年：日期按交易日折算，而不是一天一根
            LocalDate date = start.plusDays(Math.round(i * 365.25 / 250));
            bars.add(new PricePositionCalculator.Bar(date,
                    BigDecimal.valueOf(10 * Math.pow(1.10, i / 250.0))));
        }
        PricePosition p = PricePositionCalculator.compute(bars);

        assertThat(p.getThreeYearCagr().doubleValue()).isCloseTo(10, org.assertj.core.data.Offset.offset(0.5));
        assertThat(p.getFiveYearCagr().doubleValue()).isCloseTo(10, org.assertj.core.data.Offset.offset(0.5));

        // 只有一年日线时不能拿它年化后冒充「3 年 CAGR」
        PricePosition tooShort = PricePositionCalculator.compute(ramp(10, 20, 250));
        assertThat(tooShort.getThreeYearCagr()).isNull();
        assertThat(tooShort.getFiveYearCagr()).isNull();
        assertThat(tooShort.getMaxDrawdownThreeYear()).isNull();
    }

    @Test
    void threeYearDrawdownTracksWhetherThePriorPeakWasRegained() {
        List<PricePositionCalculator.Bar> recovered = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            double v = i < 100 ? 100 : i < 200 ? 100 - (i - 100) * 0.4 : 60 + (i - 200) * 0.084;
            recovered.add(new PricePositionCalculator.Bar(
                    LocalDate.of(2022, 1, 1).plusDays(i), BigDecimal.valueOf(v)));
        }
        PricePosition ok = PricePositionCalculator.compute(recovered);
        assertThat(ok.getMaxDrawdownThreeYear()).isEqualByComparingTo("40.00");
        assertThat(ok.getThreeYearDrawdownRecovered()).isTrue();

        PricePosition falling = PricePositionCalculator.compute(fallingSeries());
        assertThat(falling.getMaxDrawdownThreeYear()).isEqualByComparingTo("60.00");
        assertThat(falling.getThreeYearDrawdownRecovered()).isFalse();
    }

    /** 800 根：先横盘在 100，再一路跌到 40，最后也没有回到 100。 */
    private static List<PricePositionCalculator.Bar> fallingSeries() {
        List<PricePositionCalculator.Bar> bars = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            double v = i < 100 ? 100 : 100 - (i - 100) * 60.0 / 699;
            bars.add(new PricePositionCalculator.Bar(
                    LocalDate.of(2022, 1, 1).plusDays(i), BigDecimal.valueOf(v)));
        }
        return bars;
    }
}