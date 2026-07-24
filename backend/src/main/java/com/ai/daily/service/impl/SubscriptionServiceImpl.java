package com.ai.daily.service.impl;

import com.ai.daily.entity.Subscription;
import com.ai.daily.mapper.SubscriptionMapper;
import com.ai.daily.service.SubscriptionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

@Service
public class SubscriptionServiceImpl extends ServiceImpl<SubscriptionMapper, Subscription> implements SubscriptionService {

    @Override
    public Subscription getOrCreateForUser(Long userId) {
        LambdaQueryWrapper<Subscription> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Subscription::getUserId, userId).last("LIMIT 1");
        Subscription subscription = this.getOne(wrapper);
        if (subscription == null) {
            subscription = new Subscription();
            subscription.setUserId(userId);
            subscription.setReceiveTime("both");
            subscription.setPreferenceFields("[]");
            subscription.setEnabled(true);
            subscription.setMorningEnabled(true);
            subscription.setMorningTime(LocalTime.of(8, 15));
            subscription.setEveningEnabled(true);
            subscription.setEveningTime(LocalTime.of(20, 15));
            this.save(subscription);
        }
        boolean dirty = false;
        if (subscription.getMorningEnabled() == null) { subscription.setMorningEnabled(true); dirty = true; }
        if (subscription.getMorningTime() == null) { subscription.setMorningTime(LocalTime.of(8, 15)); dirty = true; }
        if (subscription.getEveningEnabled() == null) { subscription.setEveningEnabled(true); dirty = true; }
        if (subscription.getEveningTime() == null) { subscription.setEveningTime(LocalTime.of(20, 15)); dirty = true; }
        if (subscription.getEnabled() == null) { subscription.setEnabled(true); dirty = true; }
        if (dirty) this.updateById(subscription);
        return subscription;
    }

    @Override
    public Subscription updateForUser(Long userId,
                              String receiveTime,
                              String preferenceFields,
                              String topicSchedules,
                              Boolean enabled,
                              Boolean morningEnabled,
                              LocalTime morningTime,
                              Boolean eveningEnabled,
                              LocalTime eveningTime) {
        Subscription subscription = getOrCreateForUser(userId);
        if (receiveTime != null) subscription.setReceiveTime(receiveTime);
        subscription.setPreferenceFields(preferenceFields);
        subscription.setTopicSchedules(topicSchedules);
        if (enabled != null) subscription.setEnabled(enabled);
        if (morningEnabled != null) subscription.setMorningEnabled(morningEnabled);
        if (morningTime != null) subscription.setMorningTime(morningTime);
        if (eveningEnabled != null) subscription.setEveningEnabled(eveningEnabled);
        if (eveningTime != null) subscription.setEveningTime(eveningTime);
        this.updateById(subscription);
        return subscription;
    }

    @Override
    public List<Subscription> findDueForEdition(String edition, LocalTime nowFloor) {
        LocalTime minute = nowFloor.withSecond(0).withNano(0);
        LambdaQueryWrapper<Subscription> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Subscription::getEnabled, true);
        if ("morning".equals(edition)) {
            wrapper.eq(Subscription::getMorningEnabled, true)
                    .eq(Subscription::getMorningTime, minute);
        } else if ("evening".equals(edition)) {
            wrapper.eq(Subscription::getEveningEnabled, true)
                    .eq(Subscription::getEveningTime, minute);
        } else {
            return List.of();
        }
        return this.list(wrapper);
    }

    boolean isDueForEdition(Subscription subscription, String edition, LocalTime minute) {
        LocalTime scheduled = "morning".equals(edition)
                ? subscription.getMorningTime()
                : "evening".equals(edition) ? subscription.getEveningTime() : null;
        return sameMinute(scheduled, minute);
    }

    private boolean sameMinute(LocalTime first, LocalTime second) {
        return first != null && second != null
                && first.getHour() == second.getHour()
                && first.getMinute() == second.getMinute();
    }
}
