package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final PushChannelService pushChannelService;

    @GetMapping
    public Result<SubscriptionDTO> getSubscription() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(convertToDTO(subscriptionService.getOrCreateForUser(userId)));
    }

    @PutMapping
    public Result<SubscriptionDTO> updateSubscription(@RequestBody SubscriptionDTO dto) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");

        try {
            LocalTime morningTime = parseEditionTime(dto.getMorningTime(), "morning");
            LocalTime eveningTime = parseEditionTime(dto.getEveningTime(), "evening");
            SubscriptionPreferences.NormalizedPreferences preferences = subscriptionPreferences.normalize(dto);
            var ownedChannelIds = pushChannelService.listResponsesByUser(userId).stream()
                    .map(response -> response.getId())
                    .collect(Collectors.toSet());
            var filteredSchedules = subscriptionPreferences.filterChannelIds(preferences.schedules(), ownedChannelIds);
            String schedulesJson = subscriptionPreferences.writeSchedules(filteredSchedules);
            Subscription updated = subscriptionService.updateForUser(
                    userId,
                    dto.getReceiveTime(),
                    preferences.preferenceFieldsJson(),
                    schedulesJson,
                    dto.getEnabled(),
                    dto.getMorningEnabled(),
                    morningTime,
                    dto.getEveningEnabled(),
                    eveningTime
            );
            return Result.ok("订阅配置已更新", convertToDTO(updated));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(500, "订阅配置转换失败");
        }
    }

    private LocalTime parseEditionTime(String value, String edition) {
        String label = "morning".equals(edition) ? "早报" : "晚报";
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " Web 展示时间不能为空");
        }
        if (!value.matches("\\d{2}:\\d{2}(:\\d{2})?")) {
            throw new IllegalArgumentException(label + " Web 展示时间格式无效");
        }
        LocalTime time;
        try {
            time = value.length() == 5 ? LocalTime.parse(value + ":00") : LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " Web 展示时间格式无效");
        }
        LocalTime boundary = LocalTime.of(15, 0);
        if ("morning".equals(edition) && !time.isBefore(boundary)) {
            throw new IllegalArgumentException("早报 Web 展示时间只能在 00:00–14:59");
        }
        if ("evening".equals(edition) && time.isBefore(boundary)) {
            throw new IllegalArgumentException("晚报 Web 展示时间只能在 15:00–23:59");
        }
        return time;
    }

    private SubscriptionDTO convertToDTO(Subscription subscription) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setReceiveTime(subscription.getReceiveTime());
        dto.setEnabled(subscription.getEnabled());
        dto.setMorningEnabled(subscription.getMorningEnabled());
        dto.setMorningTime(formatTime(subscription.getMorningTime()));
        dto.setEveningEnabled(subscription.getEveningEnabled());
        dto.setEveningTime(formatTime(subscription.getEveningTime()));
        dto.setPreferenceFields(subscriptionPreferences.readPreferenceFields(subscription.getPreferenceFields()));
        dto.setTopicSchedules(subscriptionPreferences.readSchedules(subscription));
        return dto;
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }
}
