package com.ai.daily.task;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportAssemblyService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.push.PushDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledPushTaskTest {

    @Test
    void assemblesSeparatelyForEachEligibleUser() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        ReportAssemblyService assembly = mock(ReportAssemblyService.class);
        PushDispatcher dispatcher = mock(PushDispatcher.class);
        PushChannelService channels = mock(PushChannelService.class);
        UserMapper users = mock(UserMapper.class);
        ScheduledPushTask task = new ScheduledPushTask(subscriptions, preferences, channels, assembly, dispatcher, users);

        Subscription first = subscription(1L);
        Subscription second = subscription(2L);
        LocalTime now = LocalTime.of(8, 15);
        LocalDate date = LocalDate.of(2026, 7, 24);
        when(subscriptions.findDueThrough(eq(now), eq(date), any())).thenReturn(List.of(first, second));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L), user(2L)));
        when(preferences.dueDisplayTimes(first, now, Duration.ofHours(3), date)).thenReturn(List.of(now));
        when(preferences.dueDisplayTimes(second, now, Duration.ofHours(3), date)).thenReturn(List.of(now));
        when(preferences.enabledTopicItemsAt(first, now, date)).thenReturn(List.of(topic("数据库", List.of(11L))));
        when(preferences.enabledTopicItemsAt(second, now, date)).thenReturn(List.of(topic("移动端", List.of(12L))));
        when(channels.listEnabledByUser(1L)).thenReturn(List.of(channel(11L)));
        when(channels.listEnabledByUser(2L)).thenReturn(List.of(channel(12L)));
        Report databaseReport = report("数据库内容");
        Report mobileReport = report("移动端内容");
        when(assembly.assembleAndPersist(1L, date, now, List.of("数据库"))).thenReturn(databaseReport);
        when(assembly.assembleAndPersist(2L, date, now, List.of("移动端"))).thenReturn(mobileReport);
        when(assembly.assembleEphemeral(10L, date, now, List.of("数据库"))).thenReturn(databaseReport);
        when(assembly.assembleEphemeral(10L, date, now, List.of("移动端"))).thenReturn(mobileReport);
        when(dispatcher.dispatchScheduledByChannel(any(), any(), any(), any()))
                .thenReturn(new PushDispatcher.DispatchResult(1, 1, 0));

        task.dispatchDue(now, date);

        verify(dispatcher).dispatchScheduledByChannel(eq(1L), any(), eq("08:15"), eq(date));
        verify(dispatcher).dispatchScheduledByChannel(eq(2L), any(), eq("08:15"), eq(date));
    }

    @Test
    void routesDifferentTopicsToTheirAssignedChannels() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        ReportAssemblyService assembly = mock(ReportAssemblyService.class);
        PushDispatcher dispatcher = mock(PushDispatcher.class);
        PushChannelService channels = mock(PushChannelService.class);
        UserMapper users = mock(UserMapper.class);
        ScheduledPushTask task = new ScheduledPushTask(subscriptions, preferences, channels, assembly, dispatcher, users);

        Subscription subscription = subscription(1L);
        LocalDate date = LocalDate.of(2026, 7, 24);
        LocalTime now = LocalTime.of(8, 15);
        Report persisted = report("拼装简报");
        Report databaseReport = report("数据库内容");
        Report mobileReport = report("移动端内容");
        when(subscriptions.findDueThrough(eq(now), eq(date), any())).thenReturn(List.of(subscription));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L)));
        when(channels.listEnabledByUser(1L)).thenReturn(List.of(channel(11L), channel(12L)));
        when(preferences.dueDisplayTimes(subscription, now, Duration.ofHours(3), date)).thenReturn(List.of(now));
        when(preferences.enabledTopicItemsAt(subscription, now, date)).thenReturn(List.of(
                topic("数据库", List.of(11L)), topic("移动端", List.of(12L))));
        when(assembly.assembleAndPersist(1L, date, now, List.of("数据库", "移动端"))).thenReturn(persisted);
        when(assembly.assembleEphemeral(10L, date, now, List.of("数据库"))).thenReturn(databaseReport);
        when(assembly.assembleEphemeral(10L, date, now, List.of("移动端"))).thenReturn(mobileReport);
        when(dispatcher.dispatchScheduledByChannel(any(), any(), any(), any()))
                .thenReturn(new PushDispatcher.DispatchResult(2, 2, 0));

        task.dispatchDue(now, date);

        ArgumentCaptor<Map<Long, Report>> reportsByChannel = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatchScheduledByChannel(eq(1L), reportsByChannel.capture(), eq("08:15"), eq(date));
        assertThat(reportsByChannel.getValue()).containsEntry(11L, databaseReport).containsEntry(12L, mobileReport);
    }

    @Test
    void skipsDemoAndDisabledUsersAndDoesNotAssemble() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        ReportAssemblyService assembly = mock(ReportAssemblyService.class);
        PushDispatcher dispatcher = mock(PushDispatcher.class);
        PushChannelService channels = mock(PushChannelService.class);
        UserMapper users = mock(UserMapper.class);
        ScheduledPushTask task = new ScheduledPushTask(subscriptions, preferences, channels, assembly, dispatcher, users);

        Subscription demo = subscription(1L);
        Subscription disabled = subscription(2L);
        when(subscriptions.findDueThrough(any(), any(), any())).thenReturn(List.of(demo, disabled));
        User demoUser = user(1L);
        demoUser.setAccountType(User.ACCOUNT_DEMO);
        User disabledUser = user(2L);
        disabledUser.setEnabled(false);
        when(users.selectBatchIds(any())).thenReturn(List.of(demoUser, disabledUser));

        task.dispatchDue(LocalTime.of(20, 15), LocalDate.of(2026, 7, 24));

        verify(assembly, never()).assembleAndPersist(any(), any(), any(), any());
        verify(dispatcher, never()).dispatchScheduledByChannel(any(), any(), any(), any());
    }

    @Test
    void skipsPushWhenNoChannelsBound() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        ReportAssemblyService assembly = mock(ReportAssemblyService.class);
        PushDispatcher dispatcher = mock(PushDispatcher.class);
        PushChannelService channels = mock(PushChannelService.class);
        UserMapper users = mock(UserMapper.class);
        ScheduledPushTask task = new ScheduledPushTask(subscriptions, preferences, channels, assembly, dispatcher, users);

        Subscription subscription = subscription(1L);
        LocalDate date = LocalDate.of(2026, 8, 25);
        LocalTime now = LocalTime.of(8, 20);
        when(subscriptions.findDueThrough(eq(now), eq(date), any())).thenReturn(List.of(subscription));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L)));
        when(preferences.dueDisplayTimes(subscription, now, Duration.ofHours(3), date)).thenReturn(List.of(now));
        when(preferences.enabledTopicItemsAt(subscription, now, date)).thenReturn(List.of(topic("数据库", List.of())));
        when(assembly.assembleAndPersist(1L, date, now, List.of("数据库"))).thenReturn(report("网页简报"));
        when(channels.listEnabledByUser(1L)).thenReturn(List.of(channel(11L)));

        task.dispatchDue(now, date);

        verify(dispatcher, never()).dispatchScheduledByChannel(any(), any(), any(), any());
    }

    private SubscriptionDTO.TopicScheduleItemDTO topic(String name, List<Long> channelIds) {
        SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(name);
        item.setEnabled(true);
        item.setTime("08:15");
        item.setChannelIds(channelIds);
        return item;
    }

    private PushChannel channel(long id) {
        PushChannel channel = new PushChannel();
        channel.setId(id);
        channel.setEnabled(true);
        return channel;
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
        report.setEdition(Report.PERSONAL);
        report.setContent(content);
        return report;
    }
}
