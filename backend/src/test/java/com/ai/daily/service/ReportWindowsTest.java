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
}
