package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.SubscriptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 订阅配置控制器（按登录用户隔离）
 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Result<SubscriptionDTO> getSubscription() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(convertToDTO(subscriptionService.getOrCreateForUser(userId)));
    }

    @PutMapping
    public Result<String> updateSubscription(@RequestBody SubscriptionDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");

        List<String> fields = dto.getTopicSchedules() == null
                ? dto.getPreferenceFields()
                : collectEnabledTopics(dto.getTopicSchedules());
        String preferenceFields = "[]";
        String topicSchedules = null;
        try {
            if (fields != null && !fields.isEmpty()) {
                preferenceFields = objectMapper.writeValueAsString(fields);
            }
            if (dto.getTopicSchedules() != null) {
                topicSchedules = objectMapper.writeValueAsString(dto.getTopicSchedules());
            }
        } catch (JsonProcessingException e) {
            return Result.error(500, "订阅配置转换失败");
        }

        subscriptionService.updateForUser(
                userId,
                dto.getReceiveTime(),
                preferenceFields,
                topicSchedules,
                dto.getEnabled(),
                dto.getMorningEnabled(),
                parseTime(dto.getMorningTime()),
                dto.getEveningEnabled(),
                parseTime(dto.getEveningTime())
        );
        return Result.ok("订阅配置已更新", null);
    }

    private LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        // 允许 "HH:mm" 或 "HH:mm:ss"
        return s.length() <= 5 ? LocalTime.parse(s + ":00") : LocalTime.parse(s);
    }

    private SubscriptionDTO convertToDTO(Subscription s) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setReceiveTime(s.getReceiveTime());
        dto.setEnabled(s.getEnabled());
        dto.setMorningEnabled(s.getMorningEnabled());
        dto.setMorningTime(s.getMorningTime() == null ? null : s.getMorningTime().toString());
        dto.setEveningEnabled(s.getEveningEnabled());
        dto.setEveningTime(s.getEveningTime() == null ? null : s.getEveningTime().toString());

        List<String> fields = parsePreferenceFields(s.getPreferenceFields());
        dto.setPreferenceFields(fields);
        dto.setTopicSchedules(parseTopicSchedules(s, fields));
        return dto;
    }

    private List<String> parsePreferenceFields(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return objectMapper.readValue(raw, new TypeReference<>() {});
            } catch (JsonProcessingException ignored) {
                return java.util.Collections.emptyList();
            }
        }
        return java.util.Collections.emptyList();
    }

    private SubscriptionDTO.TopicSchedulesDTO parseTopicSchedules(Subscription s, List<String> fields) {
        if (s.getTopicSchedules() != null && !s.getTopicSchedules().isBlank()) {
            try {
                return objectMapper.readValue(s.getTopicSchedules(), SubscriptionDTO.TopicSchedulesDTO.class);
            } catch (JsonProcessingException ignored) {
                // fall through to legacy defaults
            }
        }
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setMorning(defaultTopicItems(fields, s.getMorningTime()));
        schedules.setEvening(defaultTopicItems(fields, s.getEveningTime()));
        return schedules;
    }

    private List<SubscriptionDTO.TopicScheduleItemDTO> defaultTopicItems(List<String> fields, LocalTime time) {
        List<SubscriptionDTO.TopicScheduleItemDTO> items = new ArrayList<>();
        for (String field : fields) {
            SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
            item.setTopic(field);
            item.setEnabled(true);
            item.setTime(time == null ? null : time.toString());
            items.add(item);
        }
        return items;
    }

    private List<String> collectEnabledTopics(SubscriptionDTO.TopicSchedulesDTO schedules) {
        Set<String> fields = new LinkedHashSet<>();
        collectEnabledTopics(fields, schedules.getMorning());
        collectEnabledTopics(fields, schedules.getEvening());
        return new ArrayList<>(fields);
    }

    private void collectEnabledTopics(Set<String> fields, List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        if (items == null) return;
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            if (Boolean.TRUE.equals(item.getEnabled()) && item.getTopic() != null && !item.getTopic().isBlank()) {
                fields.add(item.getTopic());
            }
        }
    }
}
