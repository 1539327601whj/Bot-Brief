package com.ai.daily.screener;

import com.ai.daily.screener.v2.FredRates;
import com.ai.daily.warehouse.WarehouseRepository.ConstituentEarnings;
import com.ai.daily.warehouse.WarehouseRows.PriceBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EtfResearchTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final LocalDate REPORT = LocalDate.of(2026, 9, 30);

    @Test
    void supportsOnlyWithComparableCoverageAndDoesNotDoubleCountReports() {
        var rows = List.of(row("A", 12, REPORT, 5), row("A", 12, REPORT, -90),
                row("B", 8, REPORT, 10), row("C", 5, REPORT, 20), row("D", 10, null, 0));
        var earnings = EtfResearch.summarize(rows, TODAY);

        assertThat(earnings.supported()).isTrue();
        assertThat(earnings.companies()).isEqualTo(3);
        assertThat(earnings.sampleWeight()).isEqualByComparingTo("25");
        assertThat(earnings.knownWeight()).isEqualByComparingTo("35");
        assertThat(earnings.profitYoy()).isEqualByComparingTo("9.60");
    }

    @Test
    void mixedReportingPeriodsCannotFillCoverageAndWeakProfitDoesNotPass() {
        var mixed = EtfResearch.summarize(List.of(row("A", 10, REPORT, 5),
                row("B", 10, REPORT, 5), row("C", 10, REPORT.minusMonths(3), 5)), TODAY);
        assertThat(mixed.state()).isEqualTo("UNCONFIRMED");
        assertThat(mixed.profitYoy()).isNull();

        var weak = EtfResearch.summarize(List.of(row("A", 10, REPORT, -10),
                row("B", 10, REPORT, -10), row("C", 10, REPORT, 30)), TODAY);
        assertThat(weak.state()).isEqualTo("WEAK_OR_MIXED");
        assertThat(weak.profitYoy()).isPositive();
    }

    @Test
    void aRecentImprovementDoesNotHideThreeYearContraction() {
        List<ConstituentEarnings> rows = new ArrayList<>();
        for (String stock : List.of("A", "B", "C")) {
            rows.add(row(stock, 10, REPORT, 5));
            rows.add(new ConstituentEarnings("index", TODAY.minusDays(10), stock, BigDecimal.TEN,
                    REPORT.minusYears(3), null, null, BigDecimal.valueOf(20), BigDecimal.TEN));
        }
        var earnings = EtfResearch.summarize(rows, TODAY);

        assertThat(earnings.supported()).isTrue();
        assertThat(earnings.growth().sampleWeight()).isEqualByComparingTo("30");
        assertThat(earnings.sustainedContraction()).isTrue();
        assertThat(EtfResearch.View.of(true, earnings, null, null, null).candidate()).isFalse();
    }

    @Test
    void usesPreviousTwentySessionsForVolumeAndRejectsMixedSources() {
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 20; i > 0; i--) {
            bars.add(bar(TODAY.minusDays(i), "source-a", 100));
        }
        bars.add(bar(TODAY, "source-a", 225));
        assertThat(EtfResearch.volume(bars, TODAY).volumeVsPrevious20()).isEqualByComparingTo("2.25");

        bars.set(10, bar(TODAY.minusDays(10), "source-b", 100));
        assertThat(EtfResearch.volume(bars, TODAY).volumeVsPrevious20()).isNull();
        assertThat(EtfResearch.volume(bars, TODAY.plusDays(8))).isNull();
    }

    @Test
    void goldUsesTheCompletedSessionAndItsOwnMarketEvidence() {
        FredRates rates = mock(FredRates.class);
        List<PriceBar> bars = new ArrayList<>();
        for (int i = 20; i >= 0; i--) {
            bars.add(bar(TODAY.minusDays(i), "source-a", 100));
        }
        var beforeClose = EtfResearch.gold(rates, bars, bars, TODAY.minusDays(1), TODAY);
        assertThat(beforeClose.gold().lowPrice()).isNull();
        assertThat(beforeClose.volume().date()).isEqualTo(TODAY.minusDays(1));
        verifyNoInteractions(rates);

        var afterClose = EtfResearch.gold(rates, bars, bars, TODAY, TODAY);
        assertThat(afterClose.candidate()).isFalse();
        assertThat(afterClose.volume().volumeVsPrevious20()).isEqualByComparingTo("1");
        verify(rates).compareGold(TODAY, TODAY.minusDays(20), TODAY, new BigDecimal("0.00"));
    }

    private static ConstituentEarnings row(String stock, int weight, LocalDate report, int profitYoy) {
        return new ConstituentEarnings("index", TODAY.minusDays(10), stock, BigDecimal.valueOf(weight),
                report, BigDecimal.TEN, BigDecimal.valueOf(profitYoy), BigDecimal.TEN, BigDecimal.ONE);
    }

    private static PriceBar bar(LocalDate date, String source, int volume) {
        return new PriceBar("ETF:test", date, "QFQ", null, null, null, BigDecimal.TEN,
                BigDecimal.valueOf(volume), null, null, null, source, null);
    }
}
