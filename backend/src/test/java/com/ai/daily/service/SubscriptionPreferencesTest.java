package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionPreferencesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SubscriptionPreferences preferences = new SubscriptionPreferences(objectMapper);

    @Test
    void normalizesCustomInterestsAndIgnoresLegacyTimes() throws Exception {
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
        assertThat(normalized.schedules().getMorning()).extracting(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .containsExactly("具身 智能", "AI大模型");
        assertThat(normalized.schedulesJson()).doesNotContain("time");
    }

    @Test
    void preservesAndNormalizesChannelAssignments() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO item = item("AI大模型", true);
        item.setChannelIds(Arrays.asList(12L, 12L, null, -1L, 13L));
        schedules.setMorning(List.of(item));
        schedules.setEvening(List.of());
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getMorning().get(0).getChannelIds()).containsExactly(12L, 13L);
    }

    @Test
    void keepsExplicitEmptyChannelAssignmentsDistinctFromLegacyAssignments() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        SubscriptionDTO.TopicScheduleItemDTO explicit = item("AI大模型", true);
        explicit.setChannelIds(List.of());
        SubscriptionDTO.TopicScheduleItemDTO legacy = item("数据库", true);
        schedules.setMorning(List.of(explicit, legacy));
        schedules.setEvening(List.of());
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.schedules().getMorning().get(0).getChannelIds()).isEmpty();
        assertThat(normalized.schedules().getMorning().get(1).getChannelIds()).isNull();
    }

    @Test
    void fallsBackToLegacyPreferenceFieldsWhenSchedulesAreMalformed() {
        Subscription subscription = new Subscription();
        subscription.setPreferenceFields("[\"AI大模型\",\"端侧推理\"]");
        subscription.setTopicSchedules("{broken");

        SubscriptionDTO.TopicSchedulesDTO schedules = preferences.readSchedules(subscription);

        assertThat(schedules.getMorning()).extracting(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .containsExactly("AI大模型", "端侧推理");
        assertThat(schedules.getEvening()).allMatch(item -> Boolean.TRUE.equals(item.getEnabled()));
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
    void preservesDisabledCustomInterestsButExcludesThemFromUnion() {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setMorning(List.of(item("自定义主题", false), item("AI大模型", true)));
        schedules.setEvening(List.of());
        dto.setTopicSchedules(schedules);

        SubscriptionPreferences.NormalizedPreferences normalized = preferences.normalize(dto);

        assertThat(normalized.preferenceFields()).containsExactly("AI大模型");
        assertThat(normalized.schedules().getMorning()).extracting(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .containsExactly("自定义主题", "AI大模型");
    }

    private SubscriptionDTO dtoWithTopics(int count) {
        SubscriptionDTO dto = new SubscriptionDTO();
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        List<SubscriptionDTO.TopicScheduleItemDTO> items = new ArrayList<>();
        for (int index = 0; index < count; index++) items.add(item("兴趣" + index, true));
        schedules.setMorning(items);
        schedules.setEvening(List.of());
        dto.setTopicSchedules(schedules);
        return dto;
    }

    private SubscriptionDTO.TopicScheduleItemDTO item(String topic, boolean enabled) {
        SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(topic);
        item.setEnabled(enabled);
        return item;
    }
}
