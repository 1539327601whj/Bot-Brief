package com.ai.daily.service;

import com.ai.daily.entity.Report;
import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public List<String> listTopics(String edition) {
        if (!Report.isPersonalizedEdition(edition)) return List.of();
        List<Subscription> subscriptions = subscriptionService.listEnabledForEdition(edition);
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

        Map<String, String> topics = new LinkedHashMap<>();
        for (Subscription subscription : subscriptions) {
            if (!eligibleUserIds.contains(subscription.getUserId())) continue;
            for (String topic : subscriptionPreferences.enabledTopics(subscription, edition)) {
                if (topic == null || topic.isBlank()) continue;
                topics.putIfAbsent(topic.toLowerCase(Locale.ROOT), topic);
            }
        }
        return new ArrayList<>(topics.values());
    }
}
