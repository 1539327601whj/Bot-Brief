package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;

import java.util.List;

public record TopicFocus(String topic, String intent) {

    public TopicFocus {
        topic = topic == null ? "" : topic.trim();
        intent = TopicIntents.clip(intent);
    }

    public boolean usePublicDigest() {
        return TopicIntents.usePublicDigest(topic, intent);
    }

    public static List<TopicFocus> fromTopics(List<String> topics) {
        if (topics == null) return List.of();
        return topics.stream()
                .filter(topic -> topic != null && !topic.isBlank())
                .map(topic -> new TopicFocus(topic, ""))
                .toList();
    }

    public static List<TopicFocus> fromItems(List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        if (items == null) return List.of();
        return items.stream()
                .filter(item -> item != null && item.getTopic() != null && !item.getTopic().isBlank())
                .map(item -> new TopicFocus(item.getTopic(), item.getIntent()))
                .toList();
    }
}
