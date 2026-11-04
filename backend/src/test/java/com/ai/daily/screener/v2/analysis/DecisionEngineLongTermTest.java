package com.ai.daily.screener.v2.analysis;

import com.ai.daily.warehouse.WarehouseRows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEngineLongTermTest {

    private static final LocalDate START = LocalDate.of(2019, 1, 1);

    private static WarehouseRows.PriceBar bar(LocalDate date, double close) {
        return new WarehouseRows.PriceBar("600000", date, "qfq",
                null, null, null, BigDecimal.valueOf(close), null, null, null, null, "test", null);
    }

    private static LocalDate dateAt(int i) {
        return START.plusDays(Math.round(i * 365.25 / 250));
    }

    @Test
    void steadyUptrendIsLongTermUp() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            bars.add(bar(dateAt(i), 100 * Math.pow(1.10, i / 250.0)));
        }
        Decision.LongTerm lt = DecisionEngine.longTerm(bars);

        assertThat(lt.key()).isEqualTo("UP");
        assertThat(lt.threeYearCagrPct().doubleValue()).isCloseTo(10, within(0.5));
        assertThat(lt.fiveYearCagrPct().doubleValue()).isCloseTo(10, within(0.5));
        assertThat(lt.threeYearMaxDrawdownPct()).isEqualByComparingTo("0.0");
        assertThat(lt.drawdownRecovered()).isTrue();
    }

    @Test
    void flatSeriesIsNotMistakenForLongTermUp() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            bars.add(bar(dateAt(i), 100));
        }
        Decision.LongTerm lt = DecisionEngine.longTerm(bars);

        assertThat(lt.key()).isEqualTo("FLAT_OR_DOWN");
        assertThat(lt.threeYearCagrPct()).isEqualByComparingTo("0.0");
        assertThat(lt.fiveYearCagrPct()).isEqualByComparingTo("0.0");
    }

    @Test
    void shortHistoryYieldsNoNumbersInsteadOfAnnualisingOneYear() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            bars.add(bar(dateAt(i), 100 + i));
        }
        Decision.LongTerm lt = DecisionEngine.longTerm(bars);

        assertThat(lt.key()).isEqualTo("INSUFFICIENT");
        assertThat(lt.threeYearCagrPct()).isNull();
        assertThat(lt.fiveYearCagrPct()).isNull();
        assertThat(lt.threeYearMaxDrawdownPct()).isNull();
    }

    @Test
    void threeYearRecoveryAfterFiveYearDeclineIsMixedNotUp() {
        List<WarehouseRows.PriceBar> bars = new ArrayList<>();
        for (int i = 0; i < 750; i++) {
            bars.add(bar(dateAt(i), 100 * Math.pow(0.6, i / 250.0)));
        }
        double trough = 100 * Math.pow(0.6, 749 / 250.0);
        for (int i = 750; i < 1500; i++) {
            bars.add(bar(dateAt(i), trough * Math.pow(1.4, (i - 749) / 250.0)));
        }
        Decision.LongTerm lt = DecisionEngine.longTerm(bars);

        assertThat(lt.threeYearCagrPct()).isPositive();
        assertThat(lt.fiveYearCagrPct()).isNegative();
        assertThat(lt.key()).isEqualTo("MIXED");
        assertThat(lt.summary()).contains("近 3 年年化").contains("近 5 年年化");
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
