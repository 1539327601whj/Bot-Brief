package com.ai.daily.screener.v2;

import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 个股估值分位的口径。
 *
 * <p>三件事必须钉死：负 PE 不进窗口、PB 与 PE **各算各的**、
 * 分位必须落在日期的最后一条**有效**点上。任何一项错了，页面上都只会
 * 表现为「分位差一点」，很难被发现。
 */
class ValuationMathTest {

    private static WarehouseRows.ValuationPoint point(String date, String pe, String pb) {
        return new WarehouseRows.ValuationPoint("STOCK:600519", LocalDate.parse(date),
                pe == null ? null : new BigDecimal(pe), pb == null ? null : new BigDecimal(pb),
                null, null, null, null, "eastmoney-valueanalysis", null);
    }

    @Test
    void negativePeAndNullPbAreExcludedFromTheirOwnSeries() {
        List<WarehouseRows.ValuationPoint> points = List.of(
                point("2026-01-05", "10", "1"),
                point("2026-01-06", "20", "2"),
                point("2026-01-07", "5", null),
                point("2026-01-08", "-3", "4"),
                point("2026-01-09", "15", "0"));

        ValuationMath.Percentiles p = ValuationMath.compute(points);

        assertThat(p.pePoints()).isEqualTo(4);
        assertThat(p.pbPoints()).isEqualTo(3);
        assertThat(p.windowFrom()).isEqualTo(LocalDate.of(2026, 1, 5));
        // PE：10 → 100，20 → 100，5 → 33.3333，15 → 75（4 个里 3 个不大于它）
        assertThat(p.pe().get(LocalDate.of(2026, 1, 7))).isEqualByComparingTo("33.3333");
        assertThat(ValuationMath.latestPePercentile(p, LocalDate.of(2026, 1, 9)))
                .isEqualByComparingTo("75.0000");
        // PB：1 → 100，2 → 100，4 → 100（0 与 null 都不进序列）
        assertThat(p.pb()).containsOnlyKeys(
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 8));
        assertThat(p.pb().get(LocalDate.of(2026, 1, 8))).isEqualByComparingTo("100.0000");
    }

    @Test
    void emptyInputProducesEmptyPercentilesNotZero() {
        ValuationMath.Percentiles p = ValuationMath.compute(List.of());
        assertThat(p).isSameAs(ValuationMath.Percentiles.EMPTY);
        assertThat(ValuationMath.latestPePercentile(p, LocalDate.of(2026, 1, 1))).isNull();
    }

    @Test
    void percentileForADateWithoutValidPeIsNull() {
        ValuationMath.Percentiles p = ValuationMath.compute(List.of(
                point("2026-01-05", "10", "1"),
                point("2026-01-06", null, "2")));
        assertThat(ValuationMath.latestPePercentile(p, LocalDate.of(2026, 1, 6))).isNull();
        assertThat(ValuationMath.latestPePercentile(p, LocalDate.of(2026, 1, 5)))
                .isEqualByComparingTo("100.0000");
    }
}
