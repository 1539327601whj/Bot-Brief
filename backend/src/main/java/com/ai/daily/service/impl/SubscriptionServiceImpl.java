package com.ai.daily.service.impl;

import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.mapper.SubscriptionMapper;
import com.ai.daily.service.SubscriptionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

@Service
public class SubscriptionServiceImpl extends ServiceImpl<SubscriptionMapper, Subscription> implements SubscriptionService {

    private final ObjectMapper objectMapper;

    public SubscriptionServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Subscription getOrCreateForUser(Long userId) {
        LambdaQueryWrapper<Subscription> w = new LambdaQueryWrapper<>();
        w.eq(Subscription::getUserId, userId).last("LIMIT 1");
        Subscription s = this.getOne(w);
        if (s == null) {
            s = new Subscription();
            s.setUserId(userId);
            s.setReceiveTime("both");
            s.setPreferenceFields("[]");
            s.setEnabled(true);
            s.setMorningEnabled(true);
            s.setMorningTime(LocalTime.of(8, 0));
            s.setEveningEnabled(true);
            s.setEveningTime(LocalTime.of(20, 0));
            this.save(s);
        }
        // 兼容旧数据：字段可能为 null，填默认
        boolean dirty = false;
        if (s.getMorningEnabled() == null) { s.setMorningEnabled(true); dirty = true; }
        if (s.getMorningTime() == null)    { s.setMorningTime(LocalTime.of(8, 0)); dirty = true; }
        if (s.getEveningEnabled() == null) { s.setEveningEnabled(true); dirty = true; }
        if (s.getEveningTime() == null)    { s.setEveningTime(LocalTime.of(20, 0)); dirty = true; }
        if (s.getEnabled() == null)        { s.setEnabled(true); dirty = true; }
        if (dirty) this.updateById(s);
        return s;
    }

    @Override
    public void updateForUser(Long userId,
                              String receiveTime,
                              String preferenceFields,
                              String topicSchedules,
                              Boolean enabled,
                              Boolean morningEnabled,
                              LocalTime morningTime,
                              Boolean eveningEnabled,
                              LocalTime eveningTime) {
        Subscription s = getOrCreateForUser(userId);
        if (receiveTime != null) s.setReceiveTime(receiveTime);
        s.setPreferenceFields(preferenceFields);
        s.setTopicSchedules(topicSchedules);
        if (enabled != null) s.setEnabled(enabled);
        if (morningEnabled != null) s.setMorningEnabled(morningEnabled);
        if (morningTime != null) s.setMorningTime(morningTime);
        if (eveningEnabled != null) s.setEveningEnabled(eveningEnabled);
        if (eveningTime != null) s.setEveningTime(eveningTime);
        this.updateById(s);
    }

    @Override
    public List<Subscription> findDueForEdition(String edition, LocalTime nowFloor) {
        LocalTime hm = nowFloor.withSecond(0).withNano(0);
        LambdaQueryWrapper<Subscription> w = new LambdaQueryWrapper<>();
        w.eq(Subscription::getEnabled, true);
        if ("morning".equals(edition)) {
            w.eq(Subscription::getMorningEnabled, true);
        } else if ("evening".equals(edition)) {
            w.eq(Subscription::getEveningEnabled, true);
        } else {
            return List.of();
        }
        return this.list(w).stream()
                .filter(s -> isDueForEdition(s, edition, hm))
                .toList();
    }

    private boolean isDueForEdition(Subscription s, String edition, LocalTime hm) {
        SubscriptionDTO.TopicSchedulesDTO schedules = parseTopicSchedules(s.getTopicSchedules());
        List<SubscriptionDTO.TopicScheduleItemDTO> items = schedules == null
                ? null
                : ("morning".equals(edition) ? schedules.getMorning() : schedules.getEvening());
        if (items == null || items.isEmpty()) {
            LocalTime fallback = "morning".equals(edition) ? s.getMorningTime() : s.getEveningTime();
            return fallback != null && sameMinute(fallback, hm);
        }
        return items.stream().anyMatch(item -> Boolean.TRUE.equals(item.getEnabled()) && sameMinute(parseTime(item.getTime()), hm));
    }

    private SubscriptionDTO.TopicSchedulesDTO parseTopicSchedules(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, SubscriptionDTO.TopicSchedulesDTO.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        return s.length() <= 5 ? LocalTime.parse(s + ":00") : LocalTime.parse(s);
    }

    private boolean sameMinute(LocalTime a, LocalTime b) {
        return a != null && b != null && a.getHour() == b.getHour() && a.getMinute() == b.getMinute();
    }
}
