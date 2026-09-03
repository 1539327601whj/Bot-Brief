package com.ai.daily.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PushDeliverySchemaRepairRunnerTest {

    @Test
    void createsMissingTableAndDispatchKey() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("push_channel"))
                .thenReturn(List.of("id", "user_id", "status"))
                .thenReturn(List.of("PRIMARY"));

        new PushDeliverySchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc).execute(PushDeliverySchemaRepair.createSql("push_log"));
        verify(jdbc, never()).execute(PushDeliverySchemaRepair.createSql("push_channel"));
        verify(jdbc).execute(PushDeliverySchemaRepair.addDispatchKeySql());
        verify(jdbc).execute(PushDeliverySchemaRepair.createDispatchKeyIndexSql());
    }

    @Test
    void skipsWhenSchemaAlreadyComplete() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(PushDeliverySchemaRepair.requiredTables())
                .thenReturn(List.of("id", "dispatch_key"))
                .thenReturn(List.of("PRIMARY", "uk_push_log_dispatch_key"));

        new PushDeliverySchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc, never()).execute(anyString());
    }
}
