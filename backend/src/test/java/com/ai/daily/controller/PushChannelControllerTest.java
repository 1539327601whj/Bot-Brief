package com.ai.daily.controller;

import com.ai.daily.entity.Report;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.push.PushDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushChannelControllerTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testPushPrefersPersonalBriefForNormalUser() {
        ReportService reports = mock(ReportService.class);
        PushChannelController controller = new PushChannelController(
                mock(PushChannelService.class), mock(PushDispatcher.class), reports);
        authenticate(7L, "USER", "PAID");
        Report personal = report(11L, Report.PERSONAL);
        when(reports.getLatestForUser(7L, Report.PERSONAL)).thenReturn(personal);

        assertThat(controller.testReport(7L)).isSameAs(personal);
        verify(reports, never()).getLatestByEdition(any());
    }

    @Test
    void testPushDoesNotFallBackToPublicContentForNormalUser() {
        ReportService reports = mock(ReportService.class);
        PushChannelController controller = new PushChannelController(
                mock(PushChannelService.class), mock(PushDispatcher.class), reports);
        authenticate(7L, "USER", "PAID");
        when(reports.getLatestForUser(anyLong(), eq(Report.PERSONAL))).thenReturn(null);

        assertThat(controller.testReport(7L)).isNull();
        verify(reports, never()).getLatestByEdition(any());
    }

    @Test
    void testPushFallsBackToMarketWatchForAdminWhenNoPersonalBrief() {
        ReportService reports = mock(ReportService.class);
        PushChannelController controller = new PushChannelController(
                mock(PushChannelService.class), mock(PushDispatcher.class), reports);
        authenticate(1L, "ADMIN", "PAID");
        Report market = report(3L, "market_watch_evening");
        when(reports.getLatestForUser(anyLong(), eq(Report.PERSONAL))).thenReturn(null);
        when(reports.getLatestByEdition("morning")).thenReturn(null);
        when(reports.getLatestByEdition("evening")).thenReturn(null);
        when(reports.getLatestByEdition("market_watch_evening")).thenReturn(market);

        assertThat(controller.testReport(1L)).isSameAs(market);
    }

    private static void authenticate(long userId, String role, String accountType) {
        UserPrincipal principal = new UserPrincipal(userId, "user@example.com", role, accountType, "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static Report report(long id, String edition) {
        Report report = new Report();
        report.setId(id);
        report.setEdition(edition);
        return report;
    }
}
