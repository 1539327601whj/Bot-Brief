package com.ai.daily.controller;

import com.ai.daily.dto.PushChannelResponse;
import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportWindows;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.TopicGenerationStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final PushChannelService pushChannelService;
    private final com.ai.daily.service.SubscriptionProgressService subscriptionProgressService;
    private final TopicGenerationStatusService generationStatusService;

    @GetMapping("/today-status")
    public Result<com.ai.daily.dto.SubscriptionTodayStatusDTO> todayStatus() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(subscriptionProgressService.todayStatus(userId));
    }

    @GetMapping
    public Result<SubscriptionDTO> getSubscription() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(convertToDTO(subscriptionService.getOrCreateForUser(userId)));
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            return Result.error(500, "订阅配置读取失败：" + e.getMessage());
        }
    }

    @PutMapping
    @PostMapping
    public Result<SubscriptionDTO> updateSubscription(@RequestBody SubscriptionDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");

        try {
            SubscriptionPreferences.NormalizedPreferences preferences = subscriptionPreferences.normalize(dto);
            List<PushChannelResponse> channels = pushChannelService.listResponsesByUser(userId);
            validateOneChannelPerType(preferences.schedules(), channels);
            var ownedChannelIds = channels.stream().map(PushChannelResponse::getId).collect(Collectors.toSet());
            var filteredSchedules = subscriptionPreferences.filterChannelIds(preferences.schedules(), ownedChannelIds);
            String schedulesJson = subscriptionPreferences.writeSchedules(filteredSchedules);
            List<SubscriptionDTO.TopicScheduleItemDTO> enabled = filteredSchedules.getItems() == null
                    ? List.of()
                    : filteredSchedules.getItems().stream().filter(item -> Boolean.TRUE.equals(item.getEnabled())).toList();
            LocalTime morningTime = firstTimeIn(enabled, 0, 12, LocalTime.of(8, 15));
            LocalTime eveningTime = firstTimeIn(enabled, 12, 24, LocalTime.of(20, 15));
            Subscription updated = subscriptionService.updateForUser(
                    userId,
                    dto.getReceiveTime(),
                    preferences.preferenceFieldsJson(),
                    schedulesJson,
                    dto.getEnabled(),
                    enabled.stream().anyMatch(item -> hourOf(item) < 12),
                    morningTime,
                    enabled.stream().anyMatch(item -> hourOf(item) >= 12),
                    eveningTime
            );
            reopenUnreadyTopics(enabled);
            return Result.ok("订阅配置已更新", convertToDTO(updated));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(500, "订阅配置转换失败");
        } catch (DataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            return Result.error(500, "订阅配置保存失败：" + e.getMessage());
        }
    }

    private void validateOneChannelPerType(
            SubscriptionDTO.TopicSchedulesDTO schedules, List<PushChannelResponse> channels) {
        Map<Long, String> typeById = channels.stream()
                .collect(Collectors.toMap(PushChannelResponse::getId, PushChannelResponse::getChannelType, (a, b) -> a));
        if (schedules == null || schedules.getItems() == null) return;
        for (SubscriptionDTO.TopicScheduleItemDTO item : schedules.getItems()) {
            if (item.getChannelIds() == null || item.getChannelIds().isEmpty()) continue;
            Set<String> types = new HashSet<>();
            for (Long channelId : item.getChannelIds()) {
                String type = typeById.get(channelId);
                if (type == null) continue;
                if (!types.add(type)) {
                    throw new IllegalArgumentException("同一主题每种推送方式只能绑定一个账号");
                }
            }
        }
    }

    private void reopenUnreadyTopics(List<SubscriptionDTO.TopicScheduleItemDTO> items) {
        if (generationStatusService == null || items == null || items.isEmpty()) return;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (SubscriptionDTO.TopicScheduleItemDTO item : items) {
            if (item.getTopic() == null || item.getTopic().isBlank()) continue;
            LocalTime time = parseTime(item.getTime(), null);
            if (time == null) continue;
            generationStatusService.reopenUnready(today, ReportWindows.of(time), item.getTopic());
        }
    }

    private SubscriptionDTO convertToDTO(Subscription subscription) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setReceiveTime(subscription.getReceiveTime());
        dto.setPreferenceFields(subscriptionPreferences.readPreferenceFields(subscription.getPreferenceFields()));
        dto.setTopicSchedules(subscriptionPreferences.readSchedules(subscription));
        List<SubscriptionDTO.TopicScheduleItemDTO> enabled = subscriptionPreferences.enabledTopicItems(subscription);
        dto.setEnabled(!enabled.isEmpty());
        dto.setMorningEnabled(enabled.stream().anyMatch(item -> hourOf(item) < 12));
        dto.setEveningEnabled(enabled.stream().anyMatch(item -> hourOf(item) >= 12));
        dto.setMorningTime(ReportWindows.format(firstTimeIn(enabled, 0, 12, LocalTime.of(8, 15))));
        dto.setEveningTime(ReportWindows.format(firstTimeIn(enabled, 12, 24, LocalTime.of(20, 15))));
        return dto;
    }

    private static int hourOf(SubscriptionDTO.TopicScheduleItemDTO item) {
        return parseTime(item.getTime(), LocalTime.of(8, 15)).getHour();
    }

    private static LocalTime firstTimeIn(
            List<SubscriptionDTO.TopicScheduleItemDTO> items, int startHour, int endHour, LocalTime fallback) {
        return items.stream()
                .map(item -> parseTime(item.getTime(), fallback))
                .filter(time -> time.getHour() >= startHour && time.getHour() < endHour)
                .findFirst()
                .orElse(fallback);
    }

    private static LocalTime parseTime(String value, LocalTime fallback) {
        try {
            return ReportWindows.parse(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
