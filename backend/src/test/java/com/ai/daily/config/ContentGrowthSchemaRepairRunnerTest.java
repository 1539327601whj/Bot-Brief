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

class ContentGrowthSchemaRepairRunnerTest {

    @Test
    void createsOnlyMissingTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("content_account", "content_work"));

        new ContentGrowthSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc).execute(ContentGrowthSchemaRepair.createSql("content_growth_analysis"));
        verify(jdbc).execute(ContentGrowthSchemaRepair.createSql("competitor_account"));
        verify(jdbc, never()).execute(ContentGrowthSchemaRepair.createSql("content_account"));
    }

    @Test
    void skipsWhenAllTablesExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(ContentGrowthSchemaRepair.requiredTables());

        new ContentGrowthSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc, never()).execute(anyString());
    }
}
