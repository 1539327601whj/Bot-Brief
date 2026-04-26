package com.ai.daily.service.impl;

import com.ai.daily.entity.Subscription;
import com.ai.daily.mapper.SubscriptionMapper;
import com.ai.daily.service.SubscriptionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 订阅配置 Service 实现
 */
@Service
public class SubscriptionServiceImpl extends ServiceImpl<SubscriptionMapper, Subscription> implements SubscriptionService {

    @Override
    public Subscription getSubscription() {
        // 返回第一条配置（系统只有一条）
        LambdaQueryWrapper<Subscription> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Subscription::getId).last("LIMIT 1");
        Subscription subscription = this.getOne(wrapper);
        
        // 如果没有配置，创建默认配置
        if (subscription == null) {
            subscription = new Subscription();
            subscription.setReceiveTime("both");
            subscription.setPreferenceFields("[\"AI大模型\",\"Web开发\"]");
            subscription.setEnabled(true);
            this.save(subscription);
        }
        return subscription;
    }

    @Override
    public void updateSubscription(String receiveTime, String preferenceFields, Boolean enabled) {
        Subscription subscription = getSubscription();
        subscription.setReceiveTime(receiveTime);
        subscription.setPreferenceFields(preferenceFields);
        subscription.setEnabled(enabled);
        this.updateById(subscription);
    }
}
