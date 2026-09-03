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
import static org.mockito.Mockito.when;

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

    @Test
    void publicIngestTriggersCatchUp() {
        ScheduledPushTask task = mock(ScheduledPushTask.class);
        com.ai.daily.service.ReportService reports = mock(com.ai.daily.service.ReportService.class);
        when(reports.saveReport(any(), any(), any(), any(), any(), any())).thenReturn(true);
        ReportController controller = new ReportController();
        ReflectionTestUtils.setField(controller, "scheduledPushTask", task);
        ReflectionTestUtils.setField(controller, "reportService", reports);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");

        com.ai.daily.dto.ReportPushDTO dto = new com.ai.daily.dto.ReportPushDTO();
        dto.setReportDate(LocalDate.of(2026, 9, 3));
        dto.setEdition("morning");
        dto.setTitle("早报");
        dto.setContent("正文要足够长才算实质内容abcdefghij");
        dto.setRunId("r1");

        Result<Boolean> result = ReflectionTestUtils.invokeMethod(controller, "saveReport", dto);

        assertThat(result.getCode()).isEqualTo(200);
        verify(task).catchUpToday(LocalDate.of(2026, 9, 3));
    }
}
