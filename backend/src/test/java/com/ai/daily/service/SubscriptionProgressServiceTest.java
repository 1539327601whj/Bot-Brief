package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.dto.SubscriptionTodayStatusDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.TopicGenerationStatus;
import com.ai.daily.mapper.TopicSectionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscriptionProgressServiceTest {

    @Test
    void recentMissesKeepSundaySkipVisibleOnMonday() {
        LocalDate monday = LocalDate.of(2026, 9, 7);
        LocalDate sunday = LocalDate.of(2026, 9, 6);
        Subscription subscription = subscription(7L);
        TopicGenerationStatus skipped = new TopicGenerationStatus();
        skipped.setStatus(TopicGenerationStatus.SKIPPED_NO_NEWS);
        skipped.setMessage("今天没有抓到与该主题直接相关的资讯");

        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        ReportService reports = mock(ReportService.class);
        SubscribedTopicService topics = mock(SubscribedTopicService.class);
        when(subscriptions.getOrCreateForUser(7L)).thenReturn(subscription);
        when(preferences.hasActiveTopics(subscription)).thenReturn(true);
        when(preferences.enabledTopicItemsOn(eq(subscription), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            if (date.equals(monday)) return List.of(item("马斯克", "09:00"), item("黄仁勋", "16:10"));
            if (date.equals(sunday)) return List.of(item("黄仁勋", "16:10"));
            return List.of();
        });
        when(sections.findId(any(), any(), any())).thenReturn(null);
        when(reports.getByUserEditionDateAndTime(any(), any(), any(), any())).thenReturn(null);
        when(reports.getByUserEditionDateAndTime(eq(7L), eq(Report.PERSONAL), eq(monday), eq(LocalTime.of(9, 0))))
                .thenReturn(new Report());
        when(statuses.find(any(), any(), any())).thenReturn(null);
        when(statuses.find(eq(sunday), eq(ReportWindows.W12_18), eq("黄仁勋"))).thenReturn(skipped);
        when(topics.startAt(any(), any())).thenAnswer(invocation ->
                ((LocalTime) invocation.getArgument(0)).minusMinutes(30));

        SubscriptionTodayStatusDTO dto = serviceOf(
                subscriptions, preferences, sections, statuses, reports, topics)
                .todayStatus(7L, monday, LocalTime.of(9, 19));

        assertThat(dto.getItems())
                .filteredOn(item -> "黄仁勋".equals(item.getTopic()))
                .extracting(SubscriptionTodayStatusDTO.ItemStatusDTO::getStatus)
                .containsExactly("upcoming");
        assertThat(dto.getRecentMisses())
                .extracting(SubscriptionTodayStatusDTO.ItemStatusDTO::getTopic,
                        SubscriptionTodayStatusDTO.ItemStatusDTO::getDate,
                        SubscriptionTodayStatusDTO.ItemStatusDTO::getTime,
                        SubscriptionTodayStatusDTO.ItemStatusDTO::getStatus,
                        SubscriptionTodayStatusDTO.ItemStatusDTO::getMessage)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "黄仁勋", "2026-09-06", "16:10", "skipped", "今天没有抓到与该主题直接相关的资讯"));
    }

    @Test
    void recentMissesRecordSilentGapAfterCatchUp() {
        LocalDate today = LocalDate.of(2026, 9, 7);
        LocalDate yesterday = today.minusDays(1);
        Subscription subscription = subscription(3L);
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        ReportService reports = mock(ReportService.class);
        when(subscriptions.getOrCreateForUser(3L)).thenReturn(subscription);
        when(preferences.hasActiveTopics(subscription)).thenReturn(true);
        when(preferences.enabledTopicItemsOn(eq(subscription), any())).thenAnswer(invocation ->
                yesterday.equals(invocation.getArgument(1)) ? List.of(item("黄仁勋", "16:10")) : List.of());
        when(sections.findId(any(), any(), any())).thenReturn(null);
        when(reports.getByUserEditionDateAndTime(any(), any(), any(), any())).thenReturn(null);
        when(statuses.find(any(), any(), any())).thenReturn(null);

        SubscriptionTodayStatusDTO dto = serviceOf(
                subscriptions, preferences, sections, statuses, reports,
                mock(SubscribedTopicService.class))
                .todayStatus(3L, today, LocalTime.of(9, 0));

        assertThat(dto.getRecentMisses())
                .extracting(SubscriptionTodayStatusDTO.ItemStatusDTO::getMessage)
                .containsExactly("到点后没有写成日报，也没有留下生成记录");
    }

    @Test
    void settledReasonDropsRetrySuffix() {
        assertThat(SubscriptionProgressService.settledReason(
                "今天没有抓到与该主题直接相关的资讯。约 2 分钟后再试，写成后下一分钟会补网页和推送",
                "fallback"))
                .isEqualTo("今天没有抓到与该主题直接相关的资讯");
    }

    private static SubscriptionProgressService serviceOf(
            SubscriptionService subscriptions,
            SubscriptionPreferences preferences,
            TopicSectionMapper sections,
            TopicGenerationStatusService statuses,
            ReportService reports,
            SubscribedTopicService topics) {
        OpsHeartbeatService heartbeat = mock(OpsHeartbeatService.class);
        when(heartbeat.isFresh(any())).thenReturn(true);
        return new SubscriptionProgressService(
                subscriptions,
                preferences,
                sections,
                statuses,
                reports,
                heartbeat,
                topics,
                mock(PushLogService.class),
                mock(ReportQueryService.class));
    }

    private static SubscriptionDTO.TopicScheduleItemDTO item(String topic, String time) {
        SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(topic);
        item.setEnabled(true);
        item.setTime(time);
        return item;
    }

    private static Subscription subscription(long userId) {
        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        return subscription;
    }
}
