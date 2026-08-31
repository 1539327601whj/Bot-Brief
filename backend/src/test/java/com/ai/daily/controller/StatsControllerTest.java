package com.ai.daily.controller;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatsControllerTest {

    @Test
    void normalUserWithoutSlotsHasNoDefaultMorningPush() {
        StatsController controller = controller(List.of());

        LocalDateTime next = controller.nextPushAt(
                LocalDateTime.of(2026, 8, 31, 10, 0), 7L, false, false);

        assertThat(next).isNull();
    }

    @Test
    void normalUserSeesOwnNextSlot() {
        StatsController controller = controller(List.of(item("区块链", "20:20")));

        LocalDateTime next = controller.nextPushAt(
                LocalDateTime.of(2026, 8, 31, 10, 0), 7L, false, false);

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 31, 20, 20));
    }

    @Test
    void publicDigestStillUsesFixedMorningAndEvening() {
        StatsController controller = controller(List.of());

        LocalDateTime next = controller.nextPushAt(
                LocalDateTime.of(2026, 8, 31, 10, 0), 7L, false, true);

        assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 31, 20, 0));
    }

    private static StatsController controller(List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        when(subscriptions.getOrCreateForUser(any())).thenReturn(new Subscription());
        when(preferences.enabledTopicItemsOn(any(), any())).thenReturn(items);
        StatsController controller = new StatsController();
        ReflectionTestUtils.setField(controller, "subscriptionService", subscriptions);
        ReflectionTestUtils.setField(controller, "subscriptionPreferences", preferences);
        return controller;
    }

    private static SubscriptionDTO.TopicScheduleItemDTO item(String topic, String time) {
        SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(topic);
        item.setEnabled(true);
        item.setTime(time);
        return item;
    }
}
