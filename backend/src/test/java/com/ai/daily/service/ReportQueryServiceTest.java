package com.ai.daily.service;

import com.ai.daily.entity.Report;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportQueryServiceTest {

    @Test
    void demoCanReadPublicReportsButNormalUsersCannotReadSharedAiBriefs() {
        ReportService reports = mock(ReportService.class);
        ReportQueryService service = new ReportQueryService(
                reports,
                mock(ReportAssemblyService.class),
                mock(SubscriptionService.class),
                mock(SubscriptionPreferences.class));
        Report publicMorning = new Report();
        publicMorning.setId(8L);
        publicMorning.setUserId(Report.PUBLIC_OWNER_ID);
        publicMorning.setEdition("morning");
        Report userBrief = new Report();
        userBrief.setId(9L);
        userBrief.setUserId(7L);
        userBrief.setEdition(Report.PERSONAL);
        Report market = new Report();
        market.setId(10L);
        market.setUserId(Report.PUBLIC_OWNER_ID);
        market.setEdition("market_watch_evening");
        when(reports.getById(8L)).thenReturn(publicMorning);
        when(reports.getById(9L)).thenReturn(userBrief);
        when(reports.getById(10L)).thenReturn(market);

        assertThat(service.getById(7L, true, 8L)).isSameAs(publicMorning);
        assertThat(service.getById(7L, false, 8L)).isNull();
        assertThat(service.getById(7L, false, true, 8L)).isSameAs(publicMorning);
        assertThat(service.getById(7L, false, 9L)).isSameAs(userBrief);
        assertThat(service.getById(7L, false, 10L)).isNull();
        assertThat(service.getById(7L, false, true, 10L)).isSameAs(market);
        assertThat(service.getById(7L, true, 9L)).isNull();
    }

    @Test
    void adminReadsLatestPublicDigestWithoutAssemblingPersonalBriefs() {
        ReportService reports = mock(ReportService.class);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        ReportQueryService service = new ReportQueryService(
                reports,
                mock(ReportAssemblyService.class),
                subscriptions,
                mock(SubscriptionPreferences.class));
        Report publicMorning = new Report();
        publicMorning.setId(8L);
        publicMorning.setUserId(Report.PUBLIC_OWNER_ID);
        publicMorning.setEdition("morning");
        Report market = new Report();
        market.setId(10L);
        market.setUserId(Report.PUBLIC_OWNER_ID);
        market.setEdition("market_watch_evening");
        when(reports.getLatestByEdition("morning")).thenReturn(publicMorning);
        when(reports.getLatestByEdition("market_watch_evening")).thenReturn(market);

        assertThat(service.getLatest(7L, false, true, "morning")).isSameAs(publicMorning);
        assertThat(service.getLatest(7L, false, true, "market_watch_evening")).isSameAs(market);
        assertThat(service.getLatest(7L, false, false, "morning")).isNull();
        assertThat(service.getLatest(7L, false, false, "market_watch_evening")).isNull();
        verify(subscriptions, never()).getOrCreateForUser(any());
        verify(reports, never()).getLatestPublicMarketWatch();
    }

    @Test
    void hidesTodaysPublicMarketWatchBeforeDisplayTime() {
        ReportService reports = mock(ReportService.class);
        ReportQueryService service = new ReportQueryService(
                reports,
                mock(ReportAssemblyService.class),
                mock(SubscriptionService.class),
                mock(SubscriptionPreferences.class));
        Report market = new Report();
        market.setId(10L);
        market.setUserId(Report.PUBLIC_OWNER_ID);
        market.setEdition("market_watch_evening");
        market.setReportDate(java.time.LocalDate.now().plusDays(1));
        market.setDisplayTime(java.time.LocalTime.of(18, 0));
        when(reports.getLatestByEdition("market_watch_evening")).thenReturn(market);
        when(reports.getById(10L)).thenReturn(market);

        assertThat(service.getLatest(7L, false, true, "market_watch_evening")).isNull();
        assertThat(service.getById(7L, false, true, 10L)).isNull();
    }
}
