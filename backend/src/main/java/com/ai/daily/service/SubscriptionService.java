package com.ai.daily.service;

import com.ai.daily.entity.Subscription;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.Duration;
import java.time.LocalDate;
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
     * 查询已到点或已错过任一主题时刻、仍可补推的用户。
     * {@code maxLateness} 为 null 时不限制迟到时长。
     */
    List<Subscription> findDueThrough(LocalTime nowFloor, Duration maxLateness);

    List<Subscription> findDueThrough(LocalTime nowFloor, LocalDate date, Duration maxLateness);

    /**
     * 至少有一个主题开关打开的订阅。
     */
    List<Subscription> listEnabled();
}
