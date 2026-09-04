package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionPreferencesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubscriptionPreferences preferences = new SubscriptionPreferences(objectMapper);

    @Test
    void normalizesCustomInterestsAndFlattensLegacyGroups() throws Exception {
        String json = """
                {
                  "morning": [
                    {"topic":"  具身   智能  ","enabled":true,"time":"08:00"},
                    {"topic":"AI大模型","enabled":true}
                  ],
                  "evening": [
                    {"topic":"具身 智能","enabled":false,"time":"20:30"}
                  ]
                }
                """;
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setTopicSchedules(objectMapper.readValue(json, SubscriptionDTO.TopicSchedulesDTO.class));

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.preferenceFields()).containsExactly("具身 智能", "AI大模型");
        assertThat(normalized.schedules().getItems()).extracting(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .containsExactly("具身 智能", "AI大模型", "具身 智能");
        assertThat(normalized.schedulesJson()).contains("\"time\"");
    }

    @Test
    void preservesAndNormalizesChannelAssignments() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO item = item("AI大模型", true, "08:15");
        item.setChannelIds(Arrays.asList(12L, 12L, null, -1L, 13L));
        schedules.setItems(List.of(item));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getItems().get(0).getChannelIds()).containsExactly(12L, 13L);
    }

    @Test
    void preservesTopicIntentAndRejectsOverlongIntent() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO item = item("AI科技", true, "08:00");
        item.setIntent("  只要芯片和航天  ");
        schedules.setItems(List.of(item));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getItems().get(0).getIntent()).isEqualTo("只要芯片和航天");
        assertThat(normalized.schedulesJson()).contains("只要芯片和航天");
        assertThat(normalized.schedules().getItems().get(0).getSiteVisible()).isTrue();

        item.setIntent("长".repeat(121));
        assertThatThrownBy(() -> preferences.normalize(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
    }

    @Test
    void boundChannelIdsUnionsEnabledSlots() {
        Subscription subscription = new Subscription();
        subscription.setTopicSchedules("""
                {"items":[
                  {"topic":"马斯克","enabled":true,"time":"15:10","weekdayFrom":1,"weekdayTo":5,"channelIds":[11,12]},
                  {"topic":"AI科技","enabled":true,"time":"08:00","weekdayFrom":1,"weekdayTo":7,"channelIds":[]}
                ]}
                """);

        assertThat(preferences.boundChannelIds(subscription, LocalDate.of(2026, 9, 3)))
                .containsExactly(11L, 12L);
    }

    @Test
    void etfSlotsOutsideEveningMoveToEighteen() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setItems(List.of(
                item("纳指标普沪深300ETF", true, "08:15"),
                item("市场观察", true, "09:00"),
                item("AI大模型", true, "08:15")));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getItems())
                .filteredOn(item -> DigestTopics.isEtf(item.getTopic()))
                .extracting(SubscriptionDTO.TopicScheduleItemDTO::getTime)
                .containsOnly("18:00");
        assertThat(normalized.schedules().getItems())
                .filteredOn(item -> "AI大模型".equals(item.getTopic()))
                .extracting(SubscriptionDTO.TopicScheduleItemDTO::getTime)
                .containsExactly("08:15");
    }

    @Test
    void etfMorningAndEveningMergeToOneEveningSlot() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setItems(List.of(
                item("纳指标普沪深300ETF", true, "08:00"),
                item("纳指标普沪深300ETF", true, "20:00")));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getItems()).hasSize(1);
        assertThat(normalized.schedules().getItems().get(0).getTime()).isEqualTo("20:00");
    }

    @Test
    void emptyChannelAssignmentsMeanWebOnly() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO explicit = item("AI大模型", true, "08:15");
        explicit.setChannelIds(List.of());
        SubscriptionDTO.TopicScheduleItemDTO legacy = item("数据库", true, "08:15");
        schedules.setItems(List.of(explicit, legacy));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getItems().get(0).getChannelIds()).isEmpty();
        assertThat(normalized.schedules().getItems().get(1).getChannelIds()).isEmpty();
    }

    @Test
    void fallsBackToLegacyPreferenceFieldsWhenSchedulesAreMalformed() {
        Subscription subscription = new Subscription();
        subscription.setPreferenceFields("[\"AI大模型\",\"端侧推理\"]");
        subscription.setTopicSchedules("{broken");

        SubscriptionDTO.TopicSchedulesDTO schedules = preferences.readSchedules(subscription);

        assertThat(schedules.getItems()).extracting(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .containsExactly("AI大模型", "端侧推理");
        assertThat(schedules.getItems()).allMatch(item -> Boolean.TRUE.equals(item.getEnabled()));
    }

    @Test
    void rejectsSameTopicTwiceInOneWindow() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setItems(List.of(item("AI大模型", true, "08:00"), item("AI大模型", true, "10:00")));
        dto.setTopicSchedules(schedules);

        assertThatThrownBy(() -> preferences.normalize(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一时间段");
    }

    @Test
    void rejectsTooManyOrOverlongInterests() {
        SubscriptionDTO tooMany = dtoWithTopics(21);
        assertThatThrownBy(() -> preferences.normalize(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");

        SubscriptionDTO overlong = new SubscriptionDTO();
        overlong.setPreferenceFields(List.of("长".repeat(41)));
        assertThatThrownBy(() -> preferences.normalize(overlong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("40");
    }

    @Test
    void topicSwitchNotAccountFlagControlsDue() {
        Subscription subscription = new Subscription();
        subscription.setEnabled(false);
        subscription.setTopicSchedules("{\"items\":[{\"topic\":\"AI大模型\",\"enabled\":true,\"time\":\"09:00\"}]}");

        assertThat(preferences.hasActiveTopics(subscription)).isTrue();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(9, 0), Duration.ZERO)).isTrue();

        subscription.setTopicSchedules("{\"items\":[{\"topic\":\"AI大模型\",\"enabled\":false,\"time\":\"09:00\"}]}");
        assertThat(preferences.hasActiveTopics(subscription)).isFalse();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(9, 0), Duration.ZERO)).isFalse();
    }

    @Test
    void dueTimesUseTopicClocksNotLegacyColumns() {
        Subscription subscription = new Subscription();
        subscription.setEnabled(true);
        subscription.setMorningTime(LocalTime.of(8, 15));
        subscription.setTopicSchedules("{\"items\":[{\"topic\":\"AI大模型\",\"enabled\":true,\"time\":\"09:00\"}]}");

        assertThat(preferences.isDueThrough(subscription, LocalTime.of(9, 0), Duration.ZERO)).isTrue();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(8, 15), Duration.ZERO)).isFalse();
        assertThat(preferences.dueDisplayTimes(subscription, LocalTime.of(9, 5), Duration.ofHours(3)))
                .containsExactly(LocalTime.of(9, 0));
        assertThat(preferences.dueDisplayTimes(subscription, LocalTime.of(13, 0), Duration.ofHours(3))).isEmpty();
    }

    @Test
    void weekdayRangeSkipsDaysOutsideSelection() {
        Subscription subscription = new Subscription();
        subscription.setEnabled(true);
        subscription.setTopicSchedules(
                "{\"items\":[{\"topic\":\"AI大模型\",\"enabled\":true,\"time\":\"09:00\",\"weekdayFrom\":1,\"weekdayTo\":5}]}");

        LocalDate sunday = LocalDate.of(2026, 8, 30);
        LocalDate monday = LocalDate.of(2026, 8, 31);

        assertThat(preferences.isDueThrough(subscription, LocalTime.of(9, 0), Duration.ZERO, sunday)).isFalse();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(9, 0), Duration.ZERO, monday)).isTrue();
        assertThat(preferences.dueDisplayTimes(subscription, LocalTime.of(9, 0), Duration.ZERO, sunday)).isEmpty();
        assertThat(preferences.dueDisplayTimes(subscription, LocalTime.of(9, 0), Duration.ZERO, monday))
                .containsExactly(LocalTime.of(9, 0));
    }

    @Test
    void missingWeekdaysStayEveryDayForLegacyRows() {
        Subscription subscription = new Subscription();
        subscription.setEnabled(true);
        subscription.setTopicSchedules("{\"items\":[{\"topic\":\"AI大模型\",\"enabled\":true,\"time\":\"09:00\"}]}");

        assertThat(preferences.isDueThrough(
                subscription, LocalTime.of(9, 0), Duration.ZERO, LocalDate.of(2026, 8, 30))).isTrue();
    }

    @Test
    void defaultsSiteVisibleForDigestAndKeepsCustomPrivate() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO digest = item("AI科技", true, "08:00");
        SubscriptionDTO.TopicScheduleItemDTO custom = item("AI大模型", true, "08:15");
        custom.setSiteVisible(true);
        schedules.setItems(List.of(digest, custom));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);
        assertThat(normalized.schedules().getItems().get(0).getSiteVisible()).isTrue();
        assertThat(normalized.schedules().getItems().get(1).getSiteVisible()).isTrue();

        preferences.restrictSiteVisibility(normalized.schedules(), false);
        assertThat(normalized.schedules().getItems().get(0).getSiteVisible()).isTrue();
        assertThat(normalized.schedules().getItems().get(1).getSiteVisible()).isFalse();
    }

    @Test
    void adminKeepsCustomTopicSiteVisible() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO custom = item("Web开发", true, "08:15");
        custom.setSiteVisible(true);
        schedules.setItems(List.of(custom));
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);
        preferences.restrictSiteVisibility(normalized.schedules(), true);
        assertThat(normalized.schedules().getItems().get(0).getSiteVisible()).isTrue();
    }

    private SubscriptionDTO dtoWithTopics(int count) {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        List<SubscriptionDTO.TopicScheduleItemDTO> items = new ArrayList<>();
        for (int index = 0; index < count; index++) items.add(item("兴趣" + index, true, "08:15"));
        schedules.setItems(items);
        dto.setTopicSchedules(schedules);
        return dto;
    }

    private SubscriptionDTO.TopicScheduleItemDTO item(String topic, boolean enabled, String time) {
        SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(topic);
        item.setEnabled(enabled);
        item.setTime(time);
        return item;
    }
}
