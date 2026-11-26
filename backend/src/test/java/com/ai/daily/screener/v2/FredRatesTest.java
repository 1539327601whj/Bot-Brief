package com.ai.daily.screener.v2;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FredRatesTest {
    @Test
    void usesPriorObservationsAndDoesNotReuseStaleRates() {
        var rates = FredRates.parse("""
                observation_date,DGS10
                2026-11-01,4.00
                2026-11-02,
                2026-11-20,4.25
                2026-11-24,4.40
                """, "DGS10");
        var change = FredRates.change(rates, LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-23"));
        assertThat(change.basisPoints()).isEqualByComparingTo("25");
        assertThat(change.to()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(FredRates.change(rates, LocalDate.parse("2026-11-11"),
                LocalDate.parse("2026-11-23"))).isNull();
    }
}
