package com.ai.daily.service;

import com.ai.daily.dto.DueGenerationDTO;
import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.TopicGenerationStatus;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.TopicSectionMapper;
import com.ai.daily.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscribedTopicServiceTest {

    @Test
    void usesEarliestTimeInWindowAndSkipsDemo() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        SubscribedTopicService service = serviceOf(subscriptions, preferences, users, sections, statuses, mock(ReportService.class));

        Subscription alice = subscription(1L);
        Subscription bob = subscription(2L);
        Subscription demo = subscription(3L);
        when(subscriptions.listEnabled()).thenReturn(List.of(alice, bob, demo));
        when(preferences.enabledTopicItemsOn(eq(alice), any())).thenReturn(List.of(item("AI大模型", "08:00"), item("安全", "09:00")));
        when(preferences.enabledTopicItemsOn(eq(bob), any())).thenReturn(List.of(item("安全", "07:30"), item("数据库", "08:20")));
        when(preferences.enabledTopicItemsOn(eq(demo), any())).thenReturn(List.of(item("区块链", "08:00")));
        when(users.selectBatchIds(any())).thenReturn(List.of(
                user(1L, User.ACCOUNT_NORMAL), user(2L, User.ACCOUNT_NORMAL), user(3L, User.ACCOUNT_DEMO)));

        assertThat(service.listTopics(ReportWindows.W06_12)).containsExactly("AI大模型", "安全", "数据库");
        when(sections.findId(any(), any(), any())).thenReturn(null);

        List<DueGenerationDTO> due = service.listDueGenerations(LocalDate.of(2026, 8, 29), LocalTime.of(8, 0));
        assertThat(due).extracting(DueGenerationDTO::getTopic).contains("AI大模型", "安全", "数据库");
        assertThat(due).filteredOn(item -> "安全".equals(item.getTopic()))
                .extracting(DueGenerationDTO::getGenerateAt)
                .containsExactly("07:30");
    }

    @Test
    void startsGenerationBeforeDisplayTime() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        SubscribedTopicService service = serviceOf(subscriptions, preferences, users, sections, statuses, mock(ReportService.class));

        Subscription alice = subscription(1L);
        when(subscriptions.listEnabled()).thenReturn(List.of(alice));
        when(preferences.enabledTopicItemsOn(eq(alice), any())).thenReturn(List.of(item("区块链", "20:20")));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L, User.ACCOUNT_NORMAL)));
        when(sections.findId(any(), any(), any())).thenReturn(null);

        LocalDate date = LocalDate.of(2026, 8, 29);
        assertThat(service.listDueGenerations(date, LocalTime.of(19, 49))).isEmpty();
        assertThat(service.listDueGenerations(date, LocalTime.of(19, 50)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("区块链");
        assertThat(service.startAt(LocalTime.of(20, 20))).isEqualTo(LocalTime.of(19, 50));
    }

    @Test
    void doesNotRequeueSkippedOrFailedTopicsInTheSameWindow() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        SubscribedTopicService service = serviceOf(subscriptions, preferences, users, sections, statuses, mock(ReportService.class));

        Subscription alice = subscription(1L);
        when(subscriptions.listEnabled()).thenReturn(List.of(alice));
        when(preferences.enabledTopicItemsOn(eq(alice), any())).thenReturn(List.of(
                item("区块链", "20:20"), item("安全", "20:20"), item("数据库", "20:20")));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L, User.ACCOUNT_NORMAL)));
        when(sections.findId(any(), any(), any())).thenReturn(null);
        when(statuses.find(any(), any(), eq("区块链"))).thenReturn(status(TopicGenerationStatus.SKIPPED_NO_NEWS));
        when(statuses.find(any(), any(), eq("安全"))).thenReturn(status(TopicGenerationStatus.FAILED));
        when(statuses.find(any(), any(), eq("数据库"))).thenReturn(null);

        LocalDate date = LocalDate.of(2026, 8, 29);
        assertThat(service.listDueGenerations(date, LocalTime.of(20, 0)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("数据库");
    }

    @Test
    void includesDigestTopicsUntilPublicReportExists() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        ReportService reports = mock(ReportService.class);
        SubscribedTopicService service = serviceOf(subscriptions, preferences, users, sections, statuses, reports);

        Subscription alice = subscription(1L);
        when(subscriptions.listEnabled()).thenReturn(List.of(alice));
        when(preferences.enabledTopicItemsOn(eq(alice), any())).thenReturn(List.of(
                item("AI科技", "08:00"), item("纳指标普沪深300ETF", "18:00"), item("数据库", "08:20")));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L, User.ACCOUNT_NORMAL)));
        when(sections.findId(any(), any(), any())).thenReturn(null);
        when(reports.publicReportExists(any(), any())).thenReturn(false);

        LocalDate date = LocalDate.of(2026, 8, 31);
        assertThat(service.listTopics(ReportWindows.W06_12)).containsExactly("AI科技", "数据库");
        assertThat(service.listDueGenerations(date, LocalTime.of(8, 0)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("AI科技", "数据库");

        when(reports.publicReportExists("morning", date)).thenReturn(true);
        assertThat(service.listDueGenerations(date, LocalTime.of(8, 0)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("数据库");
    }

    @Test
    void digestWithIntentIsDueAsPersonalizedSection() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        ReportService reports = mock(ReportService.class);
        SubscribedTopicService service = serviceOf(subscriptions, preferences, users, sections, statuses, reports);

        Subscription alice = subscription(1L);
        var focused = item("AI科技", "08:00");
        focused.setIntent("只要芯片和航天");
        when(subscriptions.listEnabled()).thenReturn(List.of(alice));
        when(preferences.enabledTopicItemsOn(eq(alice), any())).thenReturn(List.of(focused));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L, User.ACCOUNT_NORMAL)));
        when(sections.findId(any(), any(), any())).thenReturn(null);
        when(reports.publicReportExists(any(), any())).thenReturn(true);

        List<DueGenerationDTO> due = service.listDueGenerations(LocalDate.of(2026, 8, 31), LocalTime.of(8, 0));
        assertThat(due).extracting(DueGenerationDTO::getTopic).containsExactly("AI科技");
        assertThat(due).extracting(DueGenerationDTO::getIntent).containsExactly("只要芯片和航天");
    }

    @Test
    void etfStartsCloserToDisplayTimeAndCanRefreshBrokenReport() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        TopicSectionMapper sections = mock(TopicSectionMapper.class);
        TopicGenerationStatusService statuses = mock(TopicGenerationStatusService.class);
        ReportService reports = mock(ReportService.class);
        SubscribedTopicService service = serviceOf(subscriptions, preferences, users, sections, statuses, reports);

        Subscription alice = subscription(1L);
        when(subscriptions.listEnabled()).thenReturn(List.of(alice));
        when(preferences.enabledTopicItemsOn(eq(alice), any())).thenReturn(List.of(item("纳指标普沪深300ETF", "18:00")));
        when(users.selectBatchIds(any())).thenReturn(List.of(user(1L, User.ACCOUNT_NORMAL)));
        LocalDate date = LocalDate.of(2026, 8, 31);
        assertThat(service.listDueGenerations(date, LocalTime.of(17, 49))).isEmpty();
        assertThat(service.listDueGenerations(date, LocalTime.of(17, 50)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("纳指标普沪深300ETF");

        Report broken = new Report();
        broken.setContent("溢折价：东方财富ETF实时IOPV不可用: HTTPSConnectionPool");
        broken.setCreatedAt(java.time.LocalDateTime.of(2026, 8, 31, 10, 0));
        when(reports.publicReportExists("market_watch_evening", date)).thenReturn(true);
        when(reports.getLatestByEditionForDate("market_watch_evening", date)).thenReturn(broken);
        when(statuses.find(any(), any(), any())).thenReturn(status(TopicGenerationStatus.READY));

        assertThat(service.listDueGenerations(date, LocalTime.of(18, 0)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("纳指标普沪深300ETF");

        broken.setContent("溢价：IOPV未与行情同步；估值源：中证指数官网");
        assertThat(service.listDueGenerations(date, LocalTime.of(18, 5)))
                .extracting(DueGenerationDTO::getTopic)
                .containsExactly("纳指标普沪深300ETF");
    }

    private static SubscribedTopicService serviceOf(
            SubscriptionService subscriptions,
            SubscriptionPreferences preferences,
            UserMapper users,
            TopicSectionMapper sections,
            TopicGenerationStatusService statuses,
            ReportService reports) {
        return new SubscribedTopicService(subscriptions, preferences, users, sections, statuses, reports, 30);
    }

    private static TopicGenerationStatus status(String value) {
        TopicGenerationStatus row = new TopicGenerationStatus();
        row.setStatus(value);
        return row;
    }

    private com.ai.daily.dto.SubscriptionDTO.TopicScheduleItemDTO item(String topic, String time) {
        var item = new com.ai.daily.dto.SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(topic);
        item.setEnabled(true);
        item.setTime(time);
        return item;
    }

    private Subscription subscription(long userId) {
        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        return subscription;
    }

    private User user(long id, String accountType) {
        User user = new User();
        user.setId(id);
        user.setEnabled(true);
        user.setAccountType(accountType);
        return user;
    }
}
