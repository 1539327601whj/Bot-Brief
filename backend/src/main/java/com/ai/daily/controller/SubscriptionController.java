package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.security.SecurityUtils;
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

@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;

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
            SubscriptionPreferences.NormalizedPreferences preferences = subscriptionPreferences.normalize(dto);
            Subscription updated = subscriptionService.updateForUser(
                    userId,
                    dto.getReceiveTime(),
                    preferences.preferenceFieldsJson(),
                    preferences.schedulesJson(),
                    dto.getEnabled(),
                    dto.getMorningEnabled(),
                    parseTime(dto.getMorningTime()),
                    dto.getEveningEnabled(),
                    parseTime(dto.getEveningTime())
            );
            return Result.ok("订阅配置已更新", convertToDTO(updated));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(500, "订阅配置转换失败");
        }
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.length() <= 5 ? LocalTime.parse(value + ":00") : LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("推送时间格式无效");
        }
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
