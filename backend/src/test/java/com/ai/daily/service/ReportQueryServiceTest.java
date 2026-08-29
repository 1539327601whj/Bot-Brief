package com.ai.daily.service;

import com.ai.daily.entity.Report;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
        Report userMorning = new Report();
        userMorning.setId(9L);
        userMorning.setUserId(7L);
        userMorning.setEdition("morning");
        Report market = new Report();
        market.setId(10L);
        market.setUserId(Report.PUBLIC_OWNER_ID);
        market.setEdition("market_watch_evening");
        when(reports.getById(8L)).thenReturn(publicMorning);
        when(reports.getById(9L)).thenReturn(userMorning);
        when(reports.getById(10L)).thenReturn(market);

        assertThat(service.getById(7L, true, 8L)).isSameAs(publicMorning);
        assertThat(service.getById(7L, false, 8L)).isNull();
        assertThat(service.getById(7L, false, 9L)).isSameAs(userMorning);
        assertThat(service.getById(7L, false, 10L)).isSameAs(market);
        assertThat(service.getById(7L, true, 9L)).isNull();
    }
}
