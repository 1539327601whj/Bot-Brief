package com.ai.daily.controller;

import com.ai.daily.dto.OpsDeliveryDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.service.OpsDeliveryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportRecordDeliveryControllerTest {

    @Test
    void rejectsMissingIngestToken() {
        OpsDeliveryService ops = mock(OpsDeliveryService.class);
        ReportController controller = new ReportController();
        ReflectionTestUtils.setField(controller, "opsDeliveryService", ops);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");

        Result<Integer> result = controller.recordDelivery(null, new OpsDeliveryDTO());

        assertThat(result.getCode()).isEqualTo(401);
        verify(ops, never()).record(any());
    }

    @Test
    void recordsWhenTokenMatches() {
        OpsDeliveryService ops = mock(OpsDeliveryService.class);
        when(ops.record(any())).thenReturn(1);
        ReportController controller = new ReportController();
        ReflectionTestUtils.setField(controller, "opsDeliveryService", ops);
        ReflectionTestUtils.setField(controller, "ingestToken", "secret");

        Result<Integer> result = controller.recordDelivery("secret", new OpsDeliveryDTO());

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo(1);
        verify(ops).record(any());
    }
}
