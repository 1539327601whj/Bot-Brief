package com.ai.daily.service.push;

import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.PushLogService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushDispatcherTest {

    @Test
    void sendOneRecordsSuccess() throws Exception {
        Fixture fixture = fixture();
        when(fixture.logs.claimScheduled(eq(1L), eq(20L), eq(10L), eq("wechat"), any())).thenReturn(88L);

        fixture.dispatcher.sendOne(fixture.channel, fixture.report);

        verify(fixture.sender).send(fixture.channel, fixture.report);
        verify(fixture.logs).markSuccess(88L);
        verify(fixture.logs, never()).record(any(), any(), any(), any(), eq(true), any());
    }

    @Test
    void sendOneFallsBackToRecordWhenClaimUnavailable() throws Exception {
        Fixture fixture = fixture();
        when(fixture.logs.claimScheduled(eq(1L), eq(20L), eq(10L), eq("wechat"), any())).thenReturn(null);

        fixture.dispatcher.sendOne(fixture.channel, fixture.report);

        verify(fixture.sender).send(fixture.channel, fixture.report);
        verify(fixture.logs).record(eq(1L), eq(20L), eq(10L), eq("wechat"), eq(true), isNull(),
                startsWith("test:"));
    }

    @Test
    void sendOneRecordsSanitizedFailureAndRethrows() throws Exception {
        Fixture fixture = fixture();
        when(fixture.logs.claimScheduled(eq(1L), eq(20L), eq(10L), eq("wechat"), any())).thenReturn(88L);
        doThrow(new IllegalStateException("delivery failed https://secret.example/token"))
                .when(fixture.sender).send(fixture.channel, fixture.report);

        assertThatThrownBy(() -> fixture.dispatcher.sendOne(fixture.channel, fixture.report))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("推送服务异常")
                .hasMessageNotContaining("secret.example");

        verify(fixture.logs).markFailed(88L, "推送服务异常");
    }

    @Test
    void sendOneRecordsUnknownSenderAndRethrows() {
        PushChannelService channels = mock(PushChannelService.class);
        PushLogService logs = mock(PushLogService.class);
        PushDispatcher dispatcher = new PushDispatcher(List.of(), channels, logs);
        PushChannel channel = channel("unknown");
        Report report = report();

        assertThatThrownBy(() -> dispatcher.sendOne(channel, report))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未知渠道类型");
        verify(logs).record(eq(1L), eq(20L), eq(10L), eq("unknown"), eq(false), eq("未知渠道类型"),
                startsWith("test:"));
    }

    @Test
    void scheduledDispatchClaimsBeforeSendingAndMarksSuccess() throws Exception {
        Fixture fixture = fixture();
        when(fixture.channels.listEnabledByUser(1L)).thenReturn(List.of(fixture.channel));
        when(fixture.logs.claimScheduled(eq(1L), eq(20L), eq(10L), eq("wechat"), any())).thenReturn(99L);

        PushDispatcher.DispatchResult result = fixture.dispatcher.dispatchScheduled(
                1L, fixture.report, "morning", LocalDate.of(2026, 7, 24));

        assertThat(result).isEqualTo(new PushDispatcher.DispatchResult(1, 1, 0, 0));
        verify(fixture.sender).send(fixture.channel, fixture.report);
        verify(fixture.logs).markSuccess(99L);
    }

    @Test
    void scheduledDispatchSkipsDuplicateClaimWithoutSending() throws Exception {
        Fixture fixture = fixture();
        when(fixture.channels.listEnabledByUser(1L)).thenReturn(List.of(fixture.channel));
        when(fixture.logs.claimScheduled(anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(null);

        PushDispatcher.DispatchResult result = fixture.dispatcher.dispatchScheduled(
                1L, fixture.report, "morning", LocalDate.of(2026, 7, 24));

        assertThat(result).isEqualTo(new PushDispatcher.DispatchResult(1, 0, 0, 1));
        verify(fixture.sender, never()).send(any(), any());
        verify(fixture.logs, never()).markSuccess(anyLong());
        verify(fixture.logs, never()).markFailed(anyLong(), any());
    }

    @Test
    void scheduledFailureRemainsClaimedAndIsMarkedFailed() throws Exception {
        Fixture fixture = fixture();
        when(fixture.channels.listEnabledByUser(1L)).thenReturn(List.of(fixture.channel));
        when(fixture.logs.claimScheduled(anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(99L);
        doThrow(new IllegalStateException("企业微信返回失败"))
                .when(fixture.sender).send(fixture.channel, fixture.report);

        PushDispatcher.DispatchResult result = fixture.dispatcher.dispatchScheduled(
                1L, fixture.report, "morning", LocalDate.of(2026, 7, 24));

        assertThat(result).isEqualTo(new PushDispatcher.DispatchResult(1, 0, 1, 0));
        verify(fixture.logs).markFailed(99L, "企业微信返回失败");
    }

    private Fixture fixture() {
        ChannelSender sender = mock(ChannelSender.class);
        when(sender.type()).thenReturn("wechat");
        PushChannelService channels = mock(PushChannelService.class);
        PushLogService logs = mock(PushLogService.class);
        return new Fixture(sender, channels, logs,
                new PushDispatcher(List.of(sender), channels, logs), channel("wechat"), report());
    }

    private PushChannel channel(String type) {
        PushChannel channel = new PushChannel();
        channel.setId(10L);
        channel.setUserId(1L);
        channel.setChannelType(type);
        return channel;
    }

    private Report report() {
        Report report = new Report();
        report.setId(20L);
        return report;
    }

    private record Fixture(ChannelSender sender, PushChannelService channels, PushLogService logs,
                           PushDispatcher dispatcher, PushChannel channel, Report report) {}
}
