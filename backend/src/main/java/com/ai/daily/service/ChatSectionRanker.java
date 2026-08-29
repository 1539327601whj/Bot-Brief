package com.ai.daily.service;

import com.ai.daily.entity.TopicSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 按主题段（科技日报真正的内容单元）给问题打分。
 */
public final class ChatSectionRanker {

    private ChatSectionRanker() {
    }

    public static List<TopicSection> select(
            List<TopicSection> candidates,
            List<String> keywords,
            List<String> matchedTopics,
            int limit) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        List<Scored> scored = new ArrayList<>();
        for (TopicSection section : candidates) {
            int score = score(section, keywords, matchedTopics);
            if (score > 0) {
                scored.add(new Scored(section, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(item -> item.section().getSectionDate(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(item -> item.section().getCreatedAt(), Comparator.nullsLast(Comparator.reverseOrder())));

        List<TopicSection> matched = scored.stream().limit(Math.max(1, limit)).map(Scored::section).toList();
        if (!matched.isEmpty()) return matched;
        return candidates.stream()
                .sorted(Comparator.comparing(TopicSection::getSectionDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.min(5, Math.max(1, limit)))
                .toList();
    }

    static int score(TopicSection section, List<String> keywords, List<String> matchedTopics) {
        int value = 0;
        String topic = safe(section.getTopicKey());
        if (matchedTopics != null) {
            for (String matched : matchedTopics) {
                if (matched != null && topic.equals(matched.toLowerCase(Locale.ROOT))) {
                    value += 24;
                }
            }
        }
        String title = safe(section.getTitle());
        String summary = safe(section.getSummary());
        String content = safe(section.getContent());
        if (keywords != null) {
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) continue;
                if (topic.contains(keyword)) value += 10;
                else if (title.contains(keyword)) value += 8;
                else if (summary.contains(keyword)) value += 4;
                else if (content.contains(keyword)) value += 2;
            }
        }
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Scored(TopicSection section, int score) {
    }
}
