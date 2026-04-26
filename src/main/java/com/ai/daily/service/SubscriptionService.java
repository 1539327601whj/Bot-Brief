package com.ai.daily.service;

import com.ai.daily.entity.Subscription;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 订阅配置 Service
 */
public interface SubscriptionService extends IService<Subscription> {

    /**
     * 获取订阅配置（单条）
     */
    Subscription getSubscription();

    /**
     * 更新订阅配置
     */
    void updateSubscription(String receiveTime, String preferenceFields, Boolean enabled);
}
