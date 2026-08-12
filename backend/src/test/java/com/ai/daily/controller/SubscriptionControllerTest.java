package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.dto.SubscriptionDTO;
import com.ai.daily.entity.Subscription;
import com.ai.daily.security.UserPrincipal;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.SubscriptionPreferences;
import com.ai.daily.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalTime;
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
    private SubscriptionController controller;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        SubscriptionPreferences preferences = mock(SubscriptionPreferences.class);
        PushChannelService pushChannelService = mock(PushChannelService.class);
        controller = new SubscriptionController(subscriptionService, preferences, pushChannelService);

        UserPrincipal principal = new UserPrincipal(7L, "user@example.com", "USER", "PAID", "hash", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        SubscriptionPreferences.NormalizedPreferences normalized =
                new SubscriptionPreferences.NormalizedPreferences(List.of(), schedules(), "[]", "{}");
        when(preferences.normalize(any())).thenReturn(normalized);
        when(preferences.filterChannelIds(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(preferences.writeSchedules(any())).thenReturn("{}");
        when(pushChannelService.listResponsesByUser(anyLong())).thenReturn(List.of());
        when(subscriptionService.updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Subscription subscription = new Subscription();
                    subscription.setUserId(invocation.getArgument(0));
                    subscription.setMorningTime(invocation.getArgument(6));
                    subscription.setEveningTime(invocation.getArgument(8));
                    return subscription;
                });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsMorningAndEveningBoundaryTimes() {
        assertThat(update("00:00", "15:00").getCode()).isEqualTo(200);
        assertThat(update("14:59", "23:59").getCode()).isEqualTo(200);
        verify(subscriptionService).updateForUser(7L, null, "[]", "{}", true, true,
                LocalTime.of(0, 0), true, LocalTime.of(15, 0));
        verify(subscriptionService).updateForUser(7L, null, "[]", "{}", true, true,
                LocalTime.of(14, 59), true, LocalTime.of(23, 59));
    }

    @Test
    void rejectsMorningAtOrAfterFifteen() {
        Result<SubscriptionDTO> result = update("15:00", "20:15");

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("00:00–14:59");
        verify(subscriptionService, never()).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsEveningBeforeFifteen() {
        Result<SubscriptionDTO> result = update("08:15", "14:59");

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("15:00–23:59");
        verify(subscriptionService, never()).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidTimeFormat() {
        Result<SubscriptionDTO> result = update("8:15", "20:15");

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMessage()).contains("格式无效");
        verify(subscriptionService, never()).updateForUser(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private Result<SubscriptionDTO> update(String morningTime, String eveningTime) {
        SubscriptionDTO dto = new SubscriptionDTO();
        dto.setEnabled(true);
        dto.setMorningEnabled(true);
        dto.setMorningTime(morningTime);
        dto.setEveningEnabled(true);
        dto.setEveningTime(eveningTime);
        dto.setTopicSchedules(schedules());
        return controller.updateSubscription(dto);
    }

    private static SubscriptionDTO.TopicSchedulesDTO schedules() {
        SubscriptionDTO.TopicSchedulesDTO schedules = new SubscriptionDTO.TopicSchedulesDTO();
        schedules.setMorning(List.of());
        schedules.setEvening(List.of());
        return schedules;
    }
}
