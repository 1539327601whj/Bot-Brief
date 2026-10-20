package com.ai.daily.screener.v2;

import org.junit.jupiter.api.Test;

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
}
