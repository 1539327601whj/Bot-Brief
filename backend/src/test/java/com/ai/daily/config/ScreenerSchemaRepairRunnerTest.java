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

class ScreenerSchemaRepairRunnerTest {

    @Test
    void createsOnlyTheMissingTables() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("screener_universe_snapshot", "screener_universe_stock"));

        new ScreenerSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        // 升级上来的库：多了筛选历史这一张，另两张不重复执行
        verify(jdbc).execute(ScreenerSchemaRepair.createSql("screener_scan_history"));
        verify(jdbc, never()).execute(ScreenerSchemaRepair.createSql("screener_universe_snapshot"));
        verify(jdbc, never()).execute(ScreenerSchemaRepair.createSql("screener_universe_stock"));
    }

    @Test
    void createsNothingWhenEverythingIsAlreadyThere() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(ScreenerSchemaRepair.requiredTables());

        new ScreenerSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc, never()).execute(anyString());
    }

    /** 全新建的库：查询回空列表，三张都要建。 */
    @Test
    void createsEveryTableOnAFreshDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        new ScreenerSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        for (String table : ScreenerSchemaRepair.requiredTables()) {
            verify(jdbc).execute(ScreenerSchemaRepair.createSql(table));
        }
    }
}