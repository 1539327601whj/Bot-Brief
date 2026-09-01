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

        item.setIntent("长".repeat(121));
        assertThatThrownBy(() -> preferences.normalize(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("120");
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
