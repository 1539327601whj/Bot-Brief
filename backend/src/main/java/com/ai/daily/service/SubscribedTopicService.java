package com.ai.daily.service;

import com.ai.daily.dto.DueGenerationDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.TopicSectionMapper;
import com.ai.daily.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SubscribedTopicService {

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final UserMapper userMapper;
    private final TopicSectionMapper topicSectionMapper;
    private final int generationLeadMinutes;

    public SubscribedTopicService(
            SubscriptionService subscriptionService,
            SubscriptionPreferences subscriptionPreferences,
            UserMapper userMapper,
            TopicSectionMapper topicSectionMapper,
            @Value("${report.generation-lead-minutes:30}") int generationLeadMinutes) {
        this.subscriptionService = subscriptionService;
        this.subscriptionPreferences = subscriptionPreferences;
        this.userMapper = userMapper;
        this.topicSectionMapper = topicSectionMapper;
        this.generationLeadMinutes = Math.max(0, generationLeadMinutes);
    }

    public List<String> listTopics(String window) {
        if (!ReportWindows.isGenerationWindow(window)) return List.of();
        return planEarliest(LocalDate.now(ZoneId.of("Asia/Shanghai"))).stream()
                .filter(item -> window.equals(item.getWindow()))
                .map(DueGenerationDTO::getTopic)
                .toList();
    }

    public List<DueGenerationDTO> listDueGenerations(LocalDate date, LocalTime now) {
        if (date == null || now == null) return List.of();
        LocalTime minute = now.withSecond(0).withNano(0);
        List<DueGenerationDTO> due = new ArrayList<>();
        for (DueGenerationDTO item : planEarliest(date)) {
            LocalTime readyAt = ReportWindows.parse(item.getGenerateAt());
            if (startAt(readyAt).isAfter(minute)) continue;
            if (topicSectionMapper.findId(date, item.getWindow(), item.getTopic()) != null) continue;
            due.add(item);
        }
        return due;
    }

    LocalTime startAt(LocalTime readyAt) {
        if (readyAt == null) return LocalTime.MIN;
        if (generationLeadMinutes <= 0) return readyAt;
        LocalTime start = readyAt.minusMinutes(generationLeadMinutes);
        // 跨日时从当天 00:00 开始预生成，避免 00:10 的订阅要等到整点才开工。
        return start.isAfter(readyAt) ? LocalTime.MIN : start;
    }

    List<DueGenerationDTO> planEarliest(LocalDate date) {
        List<Subscription> subscriptions = eligibleSubscriptions();
        Map<String, DueGenerationDTO> earliest = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions) {
            for (var item : subscriptionPreferences.enabledTopicItemsOn(subscription, date)) {
                if (item.getTopic() == null || item.getTopic().isBlank()) continue;
                LocalTime time = ReportWindows.parse(item.getTime()).withSecond(0).withNano(0);
                String window = ReportWindows.of(time);
                String key = window + "|" + item.getTopic().toLowerCase(Locale.ROOT);
                DueGenerationDTO existing = earliest.get(key);
                if (existing == null || time.isBefore(ReportWindows.parse(existing.getGenerateAt()))) {
                    earliest.put(key, new DueGenerationDTO(window, item.getTopic(), ReportWindows.format(time)));
                }
            }
        }
        return new ArrayList<>(earliest.values());
    }

    private List<Subscription> eligibleSubscriptions() {
        List<Subscription> subscriptions = subscriptionService.listEnabled();
        if (subscriptions.isEmpty()) return List.of();
        Set<Long> userIds = subscriptions.stream()
                .map(Subscription::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) return List.of();
        Set<Long> eligibleUserIds = userMapper.selectBatchIds(userIds).stream()
                .filter(user -> Boolean.TRUE.equals(user.getEnabled())
                        && !User.ACCOUNT_DEMO.equals(user.getAccountType()))
                .map(User::getId)
                .collect(Collectors.toSet());
        return subscriptions.stream()
                .filter(subscription -> eligibleUserIds.contains(subscription.getUserId()))
                .toList();
    }
}
