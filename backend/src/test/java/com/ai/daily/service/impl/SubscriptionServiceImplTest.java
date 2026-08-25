package com.ai.daily.service.impl;

import com.ai.daily.entity.Subscription;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceImplTest {

    private final SubscriptionServiceImpl service = new SubscriptionServiceImpl();

    @Test
    void usesOnlyReportLevelTimeForMorningAndEvening() {
        Subscription subscription = new Subscription();
        subscription.setMorningTime(LocalTime.of(8, 15));
        subscription.setEveningTime(LocalTime.of(20, 15));
        subscription.setTopicSchedules("{\"morning\":[{\"topic\":\"AI大模型\",\"enabled\":true,\"time\":\"09:00\"}]}");

        assertThat(service.isDueForEdition(subscription, "morning", LocalTime.of(8, 15))).isTrue();
        assertThat(service.isDueForEdition(subscription, "morning", LocalTime.of(9, 0))).isFalse();
        assertThat(service.isDueForEdition(subscription, "evening", LocalTime.of(20, 15))).isTrue();
        assertThat(service.isDueForEdition(subscription, "unknown", LocalTime.of(8, 15))).isFalse();
    }

    @Test
    void catchUpIncludesRecentlyMissedTimesButNotHoursLater() {
        Subscription subscription = new Subscription();
        subscription.setMorningTime(LocalTime.of(8, 0));
        subscription.setEveningTime(LocalTime.of(20, 0));

        assertThat(service.isDueThrough(subscription, "morning", LocalTime.of(8, 5), Duration.ofHours(3))).isTrue();
        assertThat(service.isDueThrough(subscription, "morning", LocalTime.of(13, 58), Duration.ofHours(3))).isFalse();
        assertThat(service.isDueThrough(subscription, "morning", LocalTime.of(13, 58), null)).isTrue();
        assertThat(service.isDueThrough(subscription, "morning", LocalTime.of(7, 59), Duration.ofHours(3))).isFalse();
        assertThat(service.isDueThrough(subscription, "evening", LocalTime.of(20, 10), Duration.ofHours(3))).isTrue();
    }
}
