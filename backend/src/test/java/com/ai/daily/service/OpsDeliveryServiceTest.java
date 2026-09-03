package com.ai.daily.service;

import com.ai.daily.dto.OpsDeliveryDTO;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpsDeliveryServiceTest {

    @Test
    void writesLogForEachEnabledChannel() {
        ReportService reports = mock(ReportService.class);
        PushChannelService channels = mock(PushChannelService.class);
        PushLogService logs = mock(PushLogService.class);
        OpsDeliveryService service = new OpsDeliveryService(reports, channels, logs);

        Report report = new Report();
        report.setId(20L);
        when(reports.getLatestByEditionForDate("morning", LocalDate.of(2026, 9, 3))).thenReturn(report);
        when(channels.listEnabledByType("wechat")).thenReturn(List.of(channel(1L, 11L), channel(2L, 12L)));
        when(logs.claimScheduled(eq(1L), eq(20L), eq(11L), eq("wechat"), any())).thenReturn(101L);
        when(logs.claimScheduled(eq(2L), eq(20L), eq(12L), eq("wechat"), any())).thenReturn(102L);

        OpsDeliveryDTO dto = new OpsDeliveryDTO();
        dto.setEdition("morning");
        dto.setReportDate(LocalDate.of(2026, 9, 3));
        dto.setChannelType("wechat");
        dto.setSuccess(true);

        assertThat(service.record(dto)).isEqualTo(2);
        verify(logs).markSuccess(101L);
        verify(logs).markSuccess(102L);
    }

    @Test
    void skipsWhenNoReport() {
        ReportService reports = mock(ReportService.class);
        PushChannelService channels = mock(PushChannelService.class);
        PushLogService logs = mock(PushLogService.class);
        OpsDeliveryService service = new OpsDeliveryService(reports, channels, logs);

        OpsDeliveryDTO dto = new OpsDeliveryDTO();
        dto.setEdition("morning");
        dto.setReportDate(LocalDate.of(2026, 9, 3));

        assertThat(service.record(dto)).isEqualTo(0);
        verify(logs, never()).claimScheduled(any(), any(), any(), any(), any());
    }

    private PushChannel channel(long userId, long id) {
        PushChannel channel = new PushChannel();
        channel.setId(id);
        channel.setUserId(userId);
        channel.setChannelType("wechat");
        return channel;
    }
}
