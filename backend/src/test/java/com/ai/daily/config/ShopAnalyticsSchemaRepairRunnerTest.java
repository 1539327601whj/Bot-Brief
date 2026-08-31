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

class ShopAnalyticsSchemaRepairRunnerTest {

    @Test
    void createsOnlyMissingTablesAndIndex() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("shop_store", "shop_product"))
                .thenReturn(List.of("PRIMARY"));

        new ShopAnalyticsSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc).execute(ShopAnalyticsSchemaRepair.createSql("shop_sales_daily"));
        verify(jdbc).execute(ShopAnalyticsSchemaRepair.createSql("shop_ai_report"));
        verify(jdbc, never()).execute(ShopAnalyticsSchemaRepair.createSql("shop_store"));
        verify(jdbc).execute(ShopAnalyticsSchemaRepair.createProductExternalIndexSql());
    }

    @Test
    void skipsWhenTablesAndIndexExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(ShopAnalyticsSchemaRepair.requiredTables())
                .thenReturn(List.of(ShopAnalyticsSchemaRepair.PRODUCT_EXTERNAL_INDEX));

        new ShopAnalyticsSchemaRepairRunner(jdbc).run(new DefaultApplicationArguments());

        verify(jdbc, never()).execute(anyString());
    }
}
