package com.ai.daily.service;

import com.ai.daily.entity.Subscription;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubscribedTopicServiceTest {

    @Test
    void unionsEnabledTopicsFromNormalUsersAndSkipsDemo() {
        SubscriptionService subscriptions = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        UserMapper users = mock(UserMapper.class);
        SubscribedTopicService service = new SubscribedTopicService(subscriptions, preferences, users);

        Subscription alice = subscription(1L);
        Subscription bob = subscription(2L);
        Subscription demo = subscription(3L);
        when(subscriptions.listEnabledForEdition("morning")).thenReturn(List.of(alice, bob, demo));
        when(preferences.enabledTopics(alice, "morning")).thenReturn(List.of("AI大模型", "安全"));
        when(preferences.enabledTopics(bob, "morning")).thenReturn(List.of("安全", "数据库"));
        when(preferences.enabledTopics(demo, "morning")).thenReturn(List.of("区块链"));
        User normalA = user(1L, User.ACCOUNT_NORMAL);
        User normalB = user(2L, User.ACCOUNT_NORMAL);
        User demoUser = user(3L, User.ACCOUNT_DEMO);
        when(users.selectBatchIds(any())).thenReturn(List.of(normalA, normalB, demoUser));

        assertThat(service.listTopics("morning")).containsExactly("AI大模型", "安全", "数据库");
    }

    private Subscription subscription(long userId) {
        Subscription subscription = new Subscription();
        subscription.setUserId(userId);
        return subscription;
    }

    private User user(long id, String accountType) {
        User user = new User();
        user.setId(id);
        user.setEnabled(true);
        user.setAccountType(accountType);
        return user;
    }
}
