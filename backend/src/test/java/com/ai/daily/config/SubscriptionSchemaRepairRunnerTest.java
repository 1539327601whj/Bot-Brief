package com.ai.daily.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionSchemaRepairRunnerTest {

    @Test
    void addsOnlyMissingColumnsAndIndex() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("subscription"))).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("subscription")))
                .thenReturn(List.of("id", "enabled", "topic_schedules"))
                .thenReturn(List.of("PRIMARY"));

        new SubscriptionSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc).execute("ALTER TABLE subscription ADD COLUMN user_id BIGINT NOT NULL DEFAULT 1");
        verify(jdbc).execute("ALTER TABLE subscription ADD COLUMN morning_enabled TINYINT(1) NOT NULL DEFAULT 1");
        verify(jdbc).execute("CREATE UNIQUE INDEX uk_subscription_user_id ON subscription(user_id)");
        verify(jdbc, never()).execute("ALTER TABLE subscription ADD COLUMN topic_schedules JSON DEFAULT NULL");
    }

    @Test
    void skipsWhenSchemaAlreadyComplete() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("subscription"))).thenReturn(1);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("subscription")))
                .thenReturn(List.of(
                        "user_id", "morning_enabled", "morning_time",
                        "evening_enabled", "evening_time", "topic_schedules"))
                .thenReturn(List.of("uk_subscription_user_id"));

        new SubscriptionSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void failsWhenSubscriptionTableMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("subscription"))).thenReturn(0);

        assertThatThrownBy(() -> new SubscriptionSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscription");
        verify(jdbc, never()).execute(anyString());
    }
}
