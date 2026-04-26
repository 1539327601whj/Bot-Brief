package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.service.SubscriptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 订阅配置控制器
 */
@RestController
@RequestMapping("/api/subscription")
public class SubscriptionController {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 获取订阅配置
     */
    @GetMapping
    public Result<SubscriptionDTO> getSubscription() {
        Subscription subscription = subscriptionService.getSubscription();
        SubscriptionDTO dto = convertToDTO(subscription);
        return Result.ok(dto);
    }

    /**
     * 更新订阅配置
     */
    @PutMapping
    public Result<String> updateSubscription(@Valid @RequestBody SubscriptionDTO dto) {
        String preferenceFields = null;
        if (dto.getPreferenceFields() != null && !dto.getPreferenceFields().isEmpty()) {
            try {
                preferenceFields = objectMapper.writeValueAsString(dto.getPreferenceFields());
            } catch (JsonProcessingException e) {
                return Result.error(500, "偏好领域转换失败");
            }
        }
        
        subscriptionService.updateSubscription(dto.getReceiveTime(), preferenceFields, dto.getEnabled());
        return Result.ok("订阅配置已更新", null);
    }

    /**
     * 实体转 DTO
     */
    private SubscriptionDTO convertToDTO(Subscription subscription) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setReceiveTime(subscription.getReceiveTime());
        dto.setEnabled(subscription.getEnabled());
        
        // 解析 JSON 数组
        if (subscription.getPreferenceFields() != null && !subscription.getPreferenceFields().isEmpty()) {
            try {
                dto.setPreferenceFields(objectMapper.readValue(
                    subscription.getPreferenceFields(),
                    new TypeReference<java.util.List<String>>() {}
                ));
            } catch (JsonProcessingException e) {
                dto.setPreferenceFields(java.util.Collections.emptyList());
            }
        } else {
            dto.setPreferenceFields(java.util.Collections.emptyList());
        }
        
        return dto;
    }
}
