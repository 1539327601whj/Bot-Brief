package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.task.ScheduledPushTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReportDispatchDueControllerTest {

    @Test
    void rejectsMissingIngestToken() {
        ScheduledPushTask task = mock(ScheduledPushTask.class);
        ReportController controller = new ReportController();
        ReflectionTestUtils.setField(controller, "scheduledPushTask", task);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");

        Result<Boolean> result = controller.dispatchDue(null);

        assertThat(result.getCode()).isEqualTo(401);
        verify(task, never()).catchUpToday(any());
    }

    @Test
    void catchUpTodayWhenTokenMatches() {
        ScheduledPushTask task = mock(ScheduledPushTask.class);
        ReportController controller = new ReportController();
        ReflectionTestUtils.setField(controller, "scheduledPushTask", task);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");

        Result<Boolean> result = controller.dispatchDue("secret");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isTrue();
        verify(task).catchUpToday(any(LocalDate.class));
    }
}
