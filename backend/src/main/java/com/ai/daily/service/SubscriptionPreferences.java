package com.ai.daily.service;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
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
    private static final LocalTime DEFAULT_TIME = LocalTime.of(8, 15);

    private final ObjectMapper objectMapper;

    public SubscriptionPreferences(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedPreferences normalize(SubscriptionDTO dto) {
        SubscriptionDTO.TopicSchedulesDTO schedules = dto.getTopicSchedules() == null
                ? schedulesFromFields(dto.getPreferenceFields())
                : normalizeSchedules(dto.getTopicSchedules(), dto);
        List<String> enabledTopics = collectEnabledTopics(schedules);
        validateUniqueInterestCount(schedules);
        validateOneTopicPerWindow(schedules);
        try {
            return new NormalizedPreferences(
                    enabledTopics,
                    schedules,
                    objectMapper.writeValueAsString(enabledTopics),
                    objectMapper.writeValueAsString(Map.of("items", itemsOf(schedules)))
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
                return normalizeSchedules(
                        objectMapper.readValue(raw, SubscriptionDTO.TopicSchedulesDTO.class),
                        legacyTimes(subscription));
            } catch (JsonProcessingException | IllegalArgumentException ignored) {
            }
        }
        return schedulesFromFields(readPreferenceFields(subscription.getPreferenceFields()));
    }

    public List<String> enabledTopics(Subscription subscription) {
        return collectEnabledTopics(readSchedules(subscription));
    }

    public List<SubscriptionDTO.TopicScheduleItemDTO> enabledTopicItems(Subscription subscription) {
        return itemsOf(readSchedules(subscription)).stream()
                .filter(item -> Boolean.TRUE.equals(item.getEnabled()))
                .toList();
    }

    public List<String> enabledTopicsForWindow(Subscription subscription, String window) {
        if (window == null) return List.of();
        return enabledTopicItems(subscription).stream()
                .filter(item -> window.equals(ReportWindows.of(ReportWindows.parse(item.getTime()))))
                .map(SubscriptionDTO.TopicScheduleItemDTO::getTopic)
                .distinct()
                .toList();
    }

    public List<LocalTime> displayTimes(Subscription subscription) {
        return enabledTopicItems(subscription).stream()
                .map(item -> ReportWindows.parse(item.getTime()).withSecond(0).withNano(0))
                .distinct()
                .sorted()
                .toList();
    }

    public List<LocalTime> dueDisplayTimes(Subscription subscription, LocalTime now, java.time.Duration maxLateness) {
        if (!Boolean.TRUE.equals(subscription.getEnabled()) || now == null) return List.of();
        LocalTime minute = now.withSecond(0).withNano(0);
        LocalTime earliest = maxLateness == null ? LocalTime.MIN : minusClamped(minute, maxLateness);
        return displayTimes(subscription).stream()
                .filter(time -> !time.isAfter(minute) && !time.isBefore(earliest))
                .toList();
    }

    public List<SubscriptionDTO.TopicScheduleItemDTO> enabledTopicItemsAt(Subscription subscription, LocalTime time) {
        if (time == null) return List.of();
        String wanted = ReportWindows.format(time.withSecond(0).withNano(0));
        return enabledTopicItems(subscription).stream()
                .filter(item -> wanted.equals(item.getTime()))
                .toList();
    }

    public boolean isDueThrough(Subscription subscription, LocalTime now, java.time.Duration maxLateness) {
        if (!Boolean.TRUE.equals(subscription.getEnabled()) || now == null) return false;
        LocalTime minute = now.withSecond(0).withNano(0);
        LocalTime earliest = maxLateness == null ? LocalTime.MIN : minusClamped(minute, maxLateness);
        for (SubscriptionDTO.TopicScheduleItemDTO item : enabledTopicItems(subscription)) {
            LocalTime scheduled = ReportWindows.parse(item.getTime());
            if (!scheduled.isAfter(minute) && !scheduled.isBefore(earliest)) return true;
        }
        return false;
    }

    public String writeSchedules(SubscriptionDTO.TopicSchedulesDTO schedules) {
        try {
            return objectMapper.writeValueAsString(Map.of("items", itemsOf(schedules)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("订阅配置转换失败", e);
        }
    }

    public SubscriptionDTO.TopicSchedulesDTO filterChannelIds(
            SubscriptionDTO.TopicSchedulesDTO schedules, Collection<Long> ownedChannelIds) {
        Set<Long> owned = ownedChannelIds == null ? Set.of() : new LinkedHashSet<>(ownedChannelIds);
        SubscriptionDTO.TopicSchedulesDTO filtered = new SubscriptionDTO.TopicSchedulesDTO();
        filtered.setItems(itemsOf(schedules).stream().map(item -> {
            SubscriptionDTO.TopicScheduleItemDTO copy = copyItem(item);
            if (copy.getChannelIds() == null) {
                copy.setChannelIds(List.of());
            } else {
                copy.setChannelIds(copy.getChannelIds().stream()
                        .filter(id -> id != null && owned.contains(id))
                        .distinct()
                        .toList());
            }
            return copy;
        }).toList());
        return filtered;
    }

    private SubscriptionDTO.TopicSchedulesDTO normalizeSchedules(
            SubscriptionDTO.TopicSchedulesDTO source, SubscriptionDTO legacyTimes) {
        return normalizeSchedules(source, legacyTimeLookup(legacyTimes));
    }

    private SubscriptionDTO.TopicSchedulesDTO normalizeSchedules(
            SubscriptionDTO.TopicSchedulesDTO source, Map<String, LocalTime> legacyTimes) {
        List<SubscriptionDTO.TopicScheduleItemDTO> raw = new ArrayList<>();
        if (source != null && source.getItems() != null && !source.getItems().isEmpty()) {
            raw.addAll(source.getItems());
        } else if (source != null) {
            addLegacyItems(raw, source.getMorning(), legacyTimes.getOrDefault("morning", LocalTime.of(8, 15)));
            addLegacyItems(raw, source.getEvening(), legacyTimes.getOrDefault("evening", LocalTime.of(20, 15)));
        }
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setItems(normalizeItems(raw));
        return schedules;
    }

    private void addLegacyItems(
            List<SubscriptionDTO.TopicScheduleItemDTO> raw,
            List<SubscriptionDTO.TopicScheduleItemDTO> items,
            LocalTime fallback) {
        if (items == null) return;
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            if (item == null) continue;
            if (item.getTime() == null || item.getTime().isBlank()) {
                item.setTime(ReportWindows.format(fallback));
            }
            raw.add(item);
        }
    }

    private List<SubscriptionDTO.TopicScheduleItemDTO> normalizeItems(List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        Map<String, SubscriptionDTO.TopicScheduleItemDTO> unique = new LinkedHashMap<>();
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            if (item == null) continue;
            String topic = normalizeName(item.getTopic());
            if (topic.isEmpty()) continue;
            LocalTime time = item.getTime() == null || item.getTime().isBlank()
                    ? DEFAULT_TIME
                    : ReportWindows.parse(item.getTime());
            String key = topic.toLowerCase(Locale.ROOT) + "|" + ReportWindows.of(time);
            SubscriptionDTO.TopicScheduleItemDTO existing = unique.get(key);
            if (existing == null) {
                SubscriptionDTO.TopicScheduleItemDTO normalized = new SubscriptionDTO.TopicScheduleItemDTO();
                normalized.setTopic(topic);
                normalized.setEnabled(Boolean.TRUE.equals(item.getEnabled()));
                normalized.setTime(ReportWindows.format(time));
                normalized.setChannelIds(normalizeChannelIds(item.getChannelIds()));
                unique.put(key, normalized);
            } else if (!existing.getTime().equals(ReportWindows.format(time))) {
                throw new IllegalArgumentException("同一主题在同一时间段只能订阅一次");
            } else {
                if (Boolean.TRUE.equals(item.getEnabled())) existing.setEnabled(true);
                if (item.getChannelIds() != null) existing.setChannelIds(normalizeChannelIds(item.getChannelIds()));
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<Long> normalizeChannelIds(List<Long> channelIds) {
        if (channelIds == null) return List.of();
        return channelIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
    }

    private SubscriptionDTO.TopicSchedulesDTO schedulesFromFields(List<String> fields) {
        List<String> normalized = normalizeNames(fields);
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        List<SubscriptionDTO.TopicScheduleItemDTO> items = new ArrayList<>();
        for (String field : normalized) {
            SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
            item.setTopic(field);
            item.setEnabled(true);
            item.setTime(ReportWindows.format(DEFAULT_TIME));
            item.setChannelIds(List.of());
            items.add(item);
        }
        schedules.setItems(items);
        return schedules;
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
        for (SubscriptionDTO.TopicScheduleItemDTO item : itemsOf(schedules)) {
            if (Boolean.TRUE.equals(item.getEnabled())) {
                topics.putIfAbsent(item.getTopic().toLowerCase(Locale.ROOT), item.getTopic());
            }
        }
        return new ArrayList<>(topics.values());
    }

    private void validateUniqueInterestCount(SubscriptionDTO.TopicSchedulesDTO schedules) {
        Set<String> unique = new LinkedHashSet<>();
        for (SubscriptionDTO.TopicScheduleItemDTO item : itemsOf(schedules)) {
            unique.add(item.getTopic().toLowerCase(Locale.ROOT));
        }
        if (unique.size() > MAX_INTERESTS) throw new IllegalArgumentException("兴趣总数不能超过 " + MAX_INTERESTS + " 个");
    }

    private void validateOneTopicPerWindow(SubscriptionDTO.TopicSchedulesDTO schedules) {
        Set<String> seen = new LinkedHashSet<>();
        for (SubscriptionDTO.TopicScheduleItemDTO item : itemsOf(schedules)) {
            LocalTime time = ReportWindows.parse(item.getTime());
            String key = item.getTopic().toLowerCase(Locale.ROOT) + "|" + ReportWindows.of(time);
            if (!seen.add(key)) {
                throw new IllegalArgumentException("同一主题在同一时间段只能订阅一次");
            }
        }
    }

    private List<SubscriptionDTO.TopicScheduleItemDTO> itemsOf(SubscriptionDTO.TopicSchedulesDTO schedules) {
        if (schedules == null || schedules.getItems() == null) return List.of();
        return schedules.getItems();
    }

    private SubscriptionDTO.TopicScheduleItemDTO copyItem(SubscriptionDTO.TopicScheduleItemDTO item) {
        SubscriptionDTO.TopicScheduleItemDTO copy = new SubscriptionDTO.TopicScheduleItemDTO();
        copy.setTopic(item.getTopic());
        copy.setEnabled(item.getEnabled());
        copy.setTime(item.getTime());
        copy.setChannelIds(item.getChannelIds());
        return copy;
    }

    private Map<String, LocalTime> legacyTimes(Subscription subscription) {
        Map<String, LocalTime> times = new LinkedHashMap<>();
        times.put("morning", subscription.getMorningTime() != null ? subscription.getMorningTime() : LocalTime.of(8, 15));
        times.put("evening", subscription.getEveningTime() != null ? subscription.getEveningTime() : LocalTime.of(20, 15));
        return times;
    }

    private Map<String, LocalTime> legacyTimeLookup(SubscriptionDTO dto) {
        Map<String, LocalTime> times = new LinkedHashMap<>();
        times.put("morning", dto != null && dto.getMorningTime() != null && !dto.getMorningTime().isBlank()
                ? ReportWindows.parse(dto.getMorningTime()) : LocalTime.of(8, 15));
        times.put("evening", dto != null && dto.getEveningTime() != null && !dto.getEveningTime().isBlank()
                ? ReportWindows.parse(dto.getEveningTime()) : LocalTime.of(20, 15));
        return times;
    }

    private static LocalTime minusClamped(LocalTime now, java.time.Duration maxLateness) {
        LocalTime earliest = now.minus(maxLateness);
        return earliest.isAfter(now) ? LocalTime.MIN : earliest;
    }

    public record NormalizedPreferences(
            List<String> preferenceFields,
            SubscriptionDTO.TopicSchedulesDTO schedules,
            String preferenceFieldsJson,
            String schedulesJson
    ) {}
}
