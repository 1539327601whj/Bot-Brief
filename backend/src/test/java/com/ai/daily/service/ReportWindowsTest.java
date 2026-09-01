package com.ai.daily.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWindowsTest {

    @Test
    void earliestOnTimeIsFiveMinutesFromCurrentMinute() {
        assertThat(ReportWindows.earliestOnTime(LocalTime.of(15, 0, 40), 5))
                .isEqualTo(LocalTime.of(15, 5));
        assertThat(ReportWindows.earliestOnTime(LocalTime.of(15, 0), 5))
                .isEqualTo(LocalTime.of(15, 5));
        assertThat(ReportWindows.format(ReportWindows.earliestOnTime(LocalTime.of(15, 58), 5)))
                .isEqualTo("16:03");
    }

    @Test
    void windowEndIsLastMinuteOfGenerationWindow() {
        assertThat(ReportWindows.windowEnd(ReportWindows.W00_06)).isEqualTo(LocalTime.of(5, 59));
        assertThat(ReportWindows.windowEnd(ReportWindows.W06_12)).isEqualTo(LocalTime.of(11, 59));
        assertThat(ReportWindows.windowEnd(ReportWindows.W12_18)).isEqualTo(LocalTime.of(17, 59));
        assertThat(ReportWindows.windowEnd(ReportWindows.W18_24)).isEqualTo(LocalTime.of(23, 59));
    }
}
