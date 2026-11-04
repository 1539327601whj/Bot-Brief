package com.ai.daily.screener.v2;

import com.ai.daily.entity.MarketValuationHistory;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** md_instrument 里的跟踪指数键与指数池键的形态对齐。 */
class InstrumentFactsServiceTest {

    @Test
    void stripsTheWarehouseAssetPrefix() {
        // 盘后同步写的是 INDEX:SH000300，池子与估值库认的是 SH000300。
        assertThat(InstrumentFactsService.normalizeTrackingIndex("INDEX:SH000300"))
                .isEqualTo("SH000300");
        assertThat(InstrumentFactsService.normalizeTrackingIndex(" index:sz399006 "))
                .isEqualTo("SZ399006");
    }

    @Test
    void leavesPoolShapedAndUnknownValuesAlone() {
        assertThat(InstrumentFactsService.normalizeTrackingIndex("SH000300"))
                .isEqualTo("SH000300");
        assertThat(InstrumentFactsService.normalizeTrackingIndex("CSI H30533"))
                .isEqualTo("CSI H30533");
    }

    @Test
    void blankValuesBecomeNull() {
        assertThat(InstrumentFactsService.normalizeTrackingIndex(null)).isNull();
        assertThat(InstrumentFactsService.normalizeTrackingIndex("  ")).isNull();
        assertThat(InstrumentFactsService.normalizeTrackingIndex("INDEX:")).isNull();
    }

    @Test
    void shortHistoryIsThinEvenWhenItHasEnoughRows() {
        // 每日一行的本地估值库：600 行只覆盖 2 年，算不出 3/5 年分位。
        // 只看行数会误判成「够用」，所以还要看首尾日期跨度。
        assertThat(InstrumentFactsService.thinIndexHistory(null)).isTrue();
        assertThat(InstrumentFactsService.thinIndexHistory(List.of())).isTrue();
        assertThat(InstrumentFactsService.thinIndexHistory(history(600, LocalDate.of(2024, 11, 4))))
                .isTrue();
        assertThat(InstrumentFactsService.thinIndexHistory(history(499, LocalDate.of(2020, 1, 1))))
                .isTrue();
    }

    @Test
    void longEnoughSpanIsNotThin() {
        assertThat(InstrumentFactsService.thinIndexHistory(history(600, LocalDate.of(2022, 11, 4))))
                .isFalse();
    }

    private static List<MarketValuationHistory> history(int size, LocalDate first) {
        List<MarketValuationHistory> rows = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            MarketValuationHistory row = new MarketValuationHistory();
            row.setIndexCode("SH000300");
            row.setTradeDate(i == 0 ? first : i == size - 1 ? LocalDate.of(2026, 11, 4) : first.plusDays(i));
            rows.add(row);
        }
        return rows;
    }
}
