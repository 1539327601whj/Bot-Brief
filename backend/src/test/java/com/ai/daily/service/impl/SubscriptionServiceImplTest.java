package com.ai.daily.service.impl;

import com.ai.daily.entity.Subscription;
import com.ai.daily.service.SubscriptionPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceImplTest {

    @Test
    void dueCheckUsesTopicTimesNotLegacyColumns() {
        SubscriptionPreferences preferences = new SubscriptionPreferences(new ObjectMapper());
        Subscription subscription = new Subscription();
        subscription.setEnabled(true);
        subscription.setMorningTime(LocalTime.of(8, 15));
        subscription.setTopicSchedules("{\"items\":[{\"topic\":\"AI大模型\",\"enabled\":true,\"time\":\"09:00\"}]}");

        assertThat(preferences.isDueThrough(subscription, LocalTime.of(9, 0), Duration.ZERO)).isTrue();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(8, 15), Duration.ZERO)).isFalse();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(11, 0), Duration.ofHours(3))).isTrue();
        assertThat(preferences.isDueThrough(subscription, LocalTime.of(13, 0), Duration.ofHours(3))).isFalse();
    }
}
