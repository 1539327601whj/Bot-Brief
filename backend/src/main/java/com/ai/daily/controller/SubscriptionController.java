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

        String preferenceFields = "[]";
        if (dto.getPreferenceFields() != null && !dto.getPreferenceFields().isEmpty()) {
            try {
                preferenceFields = objectMapper.writeValueAsString(dto.getPreferenceFields());
            } catch (JsonProcessingException e) {
                return Result.error(500, "偏好领域转换失败");
            }
        }

        subscriptionService.updateForUser(
                userId,
                dto.getReceiveTime(),
                preferenceFields,
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

        if (s.getPreferenceFields() != null && !s.getPreferenceFields().isEmpty()) {
            try {
                dto.setPreferenceFields(objectMapper.readValue(
                        s.getPreferenceFields(), new TypeReference<>() {}));
            } catch (JsonProcessingException e) {
                dto.setPreferenceFields(java.util.Collections.emptyList());
            }
        } else {
            dto.setPreferenceFields(java.util.Collections.emptyList());
        }
        return dto;
    }
}
