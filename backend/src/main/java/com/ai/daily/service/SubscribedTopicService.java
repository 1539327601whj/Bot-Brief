package com.ai.daily.service;

import com.ai.daily.dto.DueGenerationDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.TopicSectionMapper;
import com.ai.daily.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SubscribedTopicService {

    private final SubscriptionService subscriptionService;
    private final SubscriptionPreferences subscriptionPreferences;
    private final UserMapper userMapper;
    private final TopicSectionMapper topicSectionMapper;

    public List<String> listTopics(String window) {
        if (!ReportWindows.isGenerationWindow(window)) return List.of();
        return planEarliest().stream()
                .filter(item -> window.equals(item.getWindow()))
                .map(DueGenerationDTO::getTopic)
                .toList();
    }

    public List<DueGenerationDTO> listDueGenerations(LocalDate date, LocalTime now) {
        if (date == null || now == null) return List.of();
        LocalTime minute = now.withSecond(0).withNano(0);
        List<DueGenerationDTO> due = new ArrayList<>();
        for (DueGenerationDTO item : planEarliest()) {
            LocalTime generateAt = ReportWindows.parse(item.getGenerateAt());
            if (generateAt.isAfter(minute)) continue;
            if (topicSectionMapper.findId(date, item.getWindow(), item.getTopic()) != null) continue;
            due.add(item);
        }
        return due;
    }

    List<DueGenerationDTO> planEarliest() {
        List<Subscription> subscriptions = eligibleSubscriptions();
        Map<String, DueGenerationDTO> earliest = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions) {
            for (var item : subscriptionPreferences.enabledTopicItems(subscription)) {
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
