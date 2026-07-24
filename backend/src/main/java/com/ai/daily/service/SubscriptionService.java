package com.ai.daily.service;

import com.ai.daily.entity.Subscription;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalTime;
import java.util.List;

/**
 * 订阅配置 Service（多租户）
 */
public interface SubscriptionService extends IService<Subscription> {

    /**
     * 获取指定用户的订阅（若无则创建默认并返回）
     */
    Subscription getOrCreateForUser(Long userId);

    /**
     * 更新用户的订阅配置
     */
    Subscription updateForUser(Long userId,
                       String receiveTime,
                       String preferenceFields,
                       String topicSchedules,
                       Boolean enabled,
                       Boolean morningEnabled,
                       LocalTime morningTime,
                       Boolean eveningEnabled,
                       LocalTime eveningTime);

    /**
     * 查询所有 enabled 且指定版次在当前分钟到期的用户订阅
     */
    List<Subscription> findDueForEdition(String edition, LocalTime nowFloor);
}
