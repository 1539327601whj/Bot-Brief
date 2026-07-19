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
    void updateForUser(Long userId,
                       String receiveTime,
                       String preferenceFields,
                       String topicSchedules,
                       Boolean enabled,
                       Boolean morningEnabled,
                       LocalTime morningTime,
                       Boolean eveningEnabled,
                       LocalTime eveningTime);

    /**
     * 查询所有 enabled 且指定 edition 在 [t-1min, t] 到期的用户订阅
     * （给 ScheduledPushTask 用；容许 60s 误差窗口，避免任务恰好错过整点）
     */
    List<Subscription> findDueForEdition(String edition, LocalTime nowFloor);
}
