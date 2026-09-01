package com.ai.daily.controller;

import com.ai.daily.dto.PushChannelResponse;
import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionProgressService;
import com.ai.daily.service.SubscriptionService;
import com.ai.daily.service.TopicGenerationStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionControllerTest {

    private SubscriptionService subscriptionService;
    private PushChannelService pushChannelService;
    private SubscriptionController controller;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        pushChannelService = mock(PushChannelService.class);
        controller = new SubscriptionController(
                subscriptionService,
                new SubscriptionPreferences(new ObjectMapper()),
                pushChannelService,
                mock(SubscriptionProgressService.class),
                mock(TopicGenerationStatusService.class));

        UserPrincipal principal = new UserPrincipal(7L, "user@example.com", "USER", "PAID", "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        when(pushChannelService.listResponsesByUser(anyLong())).thenReturn(List.of(
                PushChannelResponse.builder().id(11L).channelType("email").targetPreview("a@b.c").secretConfigured(false).enabled(true).build(),
                PushChannelResponse.builder().id(12L).channelType("email").targetPreview("c@d.e").secretConfigured(false).enabled(true).build()
        ));
        when(subscriptionService.updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Subscription subscription = new Subscription();
                    subscription.setUserId(invocation.getArgument(0));
                    subscription.setEnabled(true);
                    subscription.setPreferenceFields(invocation.getArgument(2));
                    subscription.setTopicSchedules(invocation.getArgument(3));
                    return subscription;
                });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsTopicTimesAcrossTheDay() {
        assertThat(update(item("AI大模型", "00:00"), item("数据库", "23:59")).getCode()).isEqualTo(200);
        verify(subscriptionService).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsTwoSlotsForSameTopicInOneWindow() {
        Result<SubscriptionDTO> result = update(item("AI大模型", "08:00"), item("AI大模型", "10:00"));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("同一时间段");
        verify(subscriptionService, never()).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsTwoAccountsOfTheSameChannelType() {
        SubscriptionDTO.TopicScheduleItemDTO item = item("AI大模型", "08:15");
        item.setChannelIds(List.of(11L, 12L));

        Result<SubscriptionDTO> result = update(item);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("每种推送方式只能绑定一个账号");
        verify(subscriptionService, never()).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidTimeFormat() {
        Result<SubscriptionDTO> result = update(item("AI大模型", "8:15"));

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("格式无效");
        verify(subscriptionService, never()).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Result<SubscriptionDTO> update(SubscriptionDTO.TopicScheduleItemDTO... items) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setEnabled(true);
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setItems(List.of(items));
        dto.setTopicSchedules(schedules);
        return controller.updateSubscription(dto);
    }

    private static SubscriptionDTO.TopicScheduleItemDTO item(String topic, String time) {
        SubscriptionDTO.TopicScheduleItemDTO item = new SubscriptionDTO.TopicScheduleItemDTO();
        item.setTopic(topic);
        item.setEnabled(true);
        item.setTime(time);
        item.setChannelIds(List.of());
        return item;
    }
}
