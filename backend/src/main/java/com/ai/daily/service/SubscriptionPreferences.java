package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SubscriptionPreferences {

    public static final int MAX_INTERESTS = 20;
    public static final int MAX_INTEREST_CODE_POINTS = 40;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ObjectMapper objectMapper;

    public SubscriptionPreferences(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedPreferences normalize(SubscriptionDTO dto) {
        SubscriptionDTO.TopicSchedulesDTO schedules = dto.getTopicSchedules() == null
                ? schedulesFromFields(dto.getPreferenceFields())
                : normalizeSchedules(dto.getTopicSchedules());
        List<String> enabledTopics = collectEnabledTopics(schedules);
        validateUniqueInterestCount(schedules);
        try {
            return new NormalizedPreferences(
                    enabledTopics,
                    schedules,
                    objectMapper.writeValueAsString(enabledTopics),
                    objectMapper.writeValueAsString(schedules)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("订阅配置转换失败", e);
        }
    }

    public List<String> readPreferenceFields(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return normalizeNames(objectMapper.readValue(raw, new TypeReference<List<String>>() {}));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return List.of();
        }
    }

    public SubscriptionDTO.TopicSchedulesDTO readSchedules(Subscription subscription) {
        String raw = subscription.getTopicSchedules();
        if (raw != null && !raw.isBlank()) {
            try {
                return normalizeSchedules(objectMapper.readValue(raw, SubscriptionDTO.TopicSchedulesDTO.class));
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
            }
        }
        return schedulesFromFields(readPreferenceFields(subscription.getPreferenceFields()));
    }

    public List<String> enabledTopics(Subscription subscription) {
        return collectEnabledTopics(readSchedules(subscription));
    }

    public List<String> enabledTopics(Subscription subscription, String edition) {
        SubscriptionDTO.TopicSchedulesDTO schedules = readSchedules(subscription);
        List<SubscriptionDTO.TopicScheduleItemDTO> items = switch (edition) {
            case "morning" -> schedules.getMorning();
            case "evening" -> schedules.getEvening();
            default -> List.of();
        };
        if (items == null) return List.of();
        return items.stream()
                .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                .map(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .toList();
    }

    private SubscriptionDTO.TopicSchedulesDTO normalizeSchedules(SubscriptionDTO.TopicSchedulesDTO source) {
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setMorning(normalizeItems(source.getMorning()));
        schedules.setEvening(normalizeItems(source.getEvening()));
        return schedules;
    }

    private List<SubscriptionDTO.TopicScheduleItemDTO> normalizeItems(List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        if (items == null) return new ArrayList<>();
        Map<String, SubscriptionDTO.TopicScheduleItemDTO> unique = new LinkedHashMap<>();
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            if (item == null) continue;
            String topic = normalizeName(item.getTopic());
            if (topic.isEmpty()) continue;
            String key = topic.toLowerCase(Locale.ROOT);
            SubscriptionDTO.TopicScheduleItemDTO existing = unique.get(key);
            if (existing == null) {
                SubscriptionDTO.TopicScheduleItemDTO normalized = new SubscriptionDTO.TopicScheduleItemDTO();
                normalized.setTopic(topic);
                normalized.setEnabled(Boolean.TRUE.equals(item.getEnabled()));
                unique.put(key, normalized);
            } else if (Boolean.TRUE.equals(item.getEnabled())) {
                existing.setEnabled(true);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private SubscriptionDTO.TopicSchedulesDTO schedulesFromFields(List<String> fields) {
        List<String> normalized = normalizeNames(fields);
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setMorning(toEnabledItems(normalized));
        schedules.setEvening(toEnabledItems(normalized));
        return schedules;
    }

    private List<SubscriptionDTO.TopicScheduleItemDTO> toEnabledItems(List<String> fields) {
        List<SubscriptionDTO.TopicScheduleItemDTO> items = new ArrayList<>();
        for (String field : fields) {
            SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
            item.setTopic(field);
            item.setEnabled(true);
            items.add(item);
        }
        return items;
    }

    private List<String> normalizeNames(List<String> names) {
        if (names == null) return List.of();
        Map<String, String> unique = new LinkedHashMap<>();
        for (String name : names) {
            String normalized = normalizeName(name);
            if (!normalized.isEmpty()) unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        if (unique.size() > MAX_INTERESTS) throw new IllegalArgumentException("兴趣总数不能超过 " + MAX_INTERESTS + " 个");
        return new ArrayList<>(unique.values());
    }

    private String normalizeName(String name) {
        if (name == null) return "";
        String normalized = WHITESPACE.matcher(name.trim()).replaceAll(" ");
        if (normalized.codePointCount(0, normalized.length()) > MAX_INTEREST_CODE_POINTS) {
            throw new IllegalArgumentException("每个兴趣不能超过 " + MAX_INTEREST_CODE_POINTS + " 个字符");
        }
        return normalized;
    }

    private List<String> collectEnabledTopics(SubscriptionDTO.TopicSchedulesDTO schedules) {
        Map<String, String> topics = new LinkedHashMap<>();
        collectEnabledTopics(topics, schedules.getMorning());
        collectEnabledTopics(topics, schedules.getEvening());
        return new ArrayList<>(topics.values());
    }

    private void collectEnabledTopics(Map<String, String> topics, List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        if (items == null) return;
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            if (Boolean.TRUE.equals(item.getEnabled())) {
                topics.putIfAbsent(item.getTopic().toLowerCase(Locale.ROOT), item.getTopic());
            }
        }
    }

    private void validateUniqueInterestCount(SubscriptionDTO.TopicSchedulesDTO schedules) {
        Set<String> unique = new LinkedHashSet<>();
        addTopics(unique, schedules.getMorning());
        addTopics(unique, schedules.getEvening());
        if (unique.size() > MAX_INTERESTS) throw new IllegalArgumentException("兴趣总数不能超过 " + MAX_INTERESTS + " 个");
    }

    private void addTopics(Set<String> unique, List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        if (items == null) return;
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            unique.add(item.getTopic().toLowerCase(Locale.ROOT));
        }
    }

    public record NormalizedPreferences(
            List<String> preferenceFields,
            SubscriptionDTO.TopicSchedulesDTO schedules,
            String preferenceFieldsJson,
            String schedulesJson
    ) {}
}
