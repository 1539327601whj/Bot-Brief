package com.ai.daily.task;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.ReportPersonalizationService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.push.PushDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledPushTaskTest {

    @Test
    void personalizesSeparatelyForEachEligibleUser() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        ReportService reports = mock(ReportService.class);
        ReportPersonalizationService personalizer = mock(ReportPersonalizationService.class);
        PushDispatcher dispatcher = mock(PushDispatcher.class);
        UserMapper users = mock(UserMapper.class);
        ScheduledPushTask task = new ScheduledPushTask(subscriptions, preferences, reports, personalizer, dispatcher, users);

        Subscription first = subscription(1L);
        Subscription second = subscription(2L);
        when(subscriptions.findDueForEdition("morning", LocalTime.of(8, 15))).thenReturn(List.of(first, second));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L), user(2L)));
        Report canonical = report("公共简报");
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(reports.getLatestByEditionForDate("morning", date)).thenReturn(canonical);
        when(preferences.enabledTopics(first, "morning")).thenReturn(List.of("数据库"));
        when(preferences.enabledTopics(second, "morning")).thenReturn(List.of("移动端"));
        Report databaseReport = report("数据库内容");
        Report mobileReport = report("移动端内容");
        ReportPersonalizationService.PreparedReport prepared = new ReportPersonalizationService.PreparedReport(canonical, "", List.of());
        when(personalizer.prepare(canonical)).thenReturn(prepared);
        when(personalizer.personalize(prepared, List.of("数据库"))).thenReturn(databaseReport);
        when(personalizer.personalize(prepared, List.of("移动端"))).thenReturn(mobileReport);
        when(dispatcher.dispatchScheduled(any(), any(), any(), any()))
                .thenReturn(new PushDispatcher.DispatchResult(1, 1, 0));

        task.dispatchEdition("morning", LocalTime.of(8, 15), date);

        verify(dispatcher).dispatchScheduled(1L, databaseReport, "morning", date);
        verify(dispatcher).dispatchScheduled(2L, mobileReport, "morning", date);
        assertThat(canonical.getContent()).isEqualTo("公共简报");
    }

    @Test
    void skipsDemoAndDisabledUsersAndDoesNotLoadReport() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        ReportService reports = mock(ReportService.class);
        ReportPersonalizationService personalizer = mock(ReportPersonalizationService.class);
        PushDispatcher dispatcher = mock(PushDispatcher.class);
        UserMapper users = mock(UserMapper.class);
        ScheduledPushTask task = new ScheduledPushTask(subscriptions, preferences, reports, personalizer, dispatcher, users);

        Subscription demo = subscription(1L);
        Subscription disabled = subscription(2L);
        when(subscriptions.findDueForEdition(eq("evening"), any())).thenReturn(List.of(demo, disabled));
        User demoUser = user(1L);
        demoUser.setAccountType(User.ACCOUNT_DEMO);
        User disabledUser = user(2L);
        disabledUser.setEnabled(false);
        when(users.selectBatchIds(any())).thenReturn(List.of(demoUser, disabledUser));

        task.dispatchEdition("evening", LocalTime.of(20, 15), LocalDate.of(2026, 7, 24));

        verify(reports, never()).getLatestByEditionForDate(any(), any());
        verify(dispatcher, never()).dispatchScheduled(any(), any(), any(), any());
    }

    private Subscription subscription(long userId) {
        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        return subscription;
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEnabled(true);
        user.setAccountType(User.ACCOUNT_NORMAL);
        return user;
    }

    private Report report(String content) {
        Report report = new Report();
        report.setId(10L);
        report.setEdition("morning");
        report.setContent(content);
        return report;
    }
}
