package com.ai.daily.warehouse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启动时的数据底座检查。
 *
 * <p>它**只检查、不自动建表**：缺表时 v2 返回 503，v1 与其它功能照常。
 * 这里钉住三件事：识别缺表、识别缺列、DB 本身不可用时也不让应用起不来。
 */
class WarehouseSchemaGuardTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void readyWhenAllTablesAndColumnsExist() {
        stubSchema(new ArrayList<>(WarehouseSchemaGuard.REQUIRED.keySet()),
                WarehouseSchemaGuard.REQUIRED);

        WarehouseSchemaGuard guard = new WarehouseSchemaGuard(jdbcTemplate);
        guard.check();

        assertThat(guard.isReady()).isTrue();
        assertThat(guard.missingTables()).isEmpty();
        assertThat(guard.missingColumns()).isEmpty();
        assertThat(guard.unavailableReason()).isNull();
    }

    @Test
    void reportsMissingTablesWithTheMigrationFile() {
        List<String> tables = new ArrayList<>(WarehouseSchemaGuard.REQUIRED.keySet());
        tables.remove("md_event");
        stubSchema(tables, WarehouseSchemaGuard.REQUIRED);

        WarehouseSchemaGuard guard = new WarehouseSchemaGuard(jdbcTemplate);
        guard.check();

        assertThat(guard.isReady()).isFalse();
        assertThat(guard.missingTables()).containsExactly("md_event");
        assertThat(guard.unavailableReason())
                .contains(WarehouseSchemaGuard.MIGRATION_FILE)
                .contains("md_event");
    }

    @Test
    void reportsMissingColumnsOnAnExistingTable() {
        Map<String, List<String>> columns = new LinkedHashMap<>(WarehouseSchemaGuard.REQUIRED);
        List<String> withoutPb = new ArrayList<>(columns.get("market_valuation_history"));
        withoutPb.remove("pb");
        columns.put("market_valuation_history", withoutPb);

        stubSchema(new ArrayList<>(WarehouseSchemaGuard.REQUIRED.keySet()), columns);

        WarehouseSchemaGuard guard = new WarehouseSchemaGuard(jdbcTemplate);
        guard.check();

        assertThat(guard.isReady()).isFalse();
        assertThat(guard.missingTables()).isEmpty();
        assertThat(guard.missingColumns())
                .containsEntry("market_valuation_history", List.of("pb"));
        assertThat(guard.unavailableReason()).contains("market_valuation_history(pb)");
    }

    @Test
    void aDatabaseFailureDoesNotThrowOutOfStartup() {
        // 最要紧的是这一条：检查失败 ≠ 应用失败。v1 必须继续可用。
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("db down"));

        WarehouseSchemaGuard guard = new WarehouseSchemaGuard(jdbcTemplate);
        guard.check();

        assertThat(guard.isReady()).isFalse();
        assertThat(guard.unavailableReason()).contains("检查数据底座失败").contains("db down");
    }

    @Test
    void checkIsIdempotent() {
        stubSchema(new ArrayList<>(WarehouseSchemaGuard.REQUIRED.keySet()),
                WarehouseSchemaGuard.REQUIRED);

        WarehouseSchemaGuard guard = new WarehouseSchemaGuard(jdbcTemplate);
        guard.check();
        guard.check();

        // 第二次不应再查 information_schema
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(1))
                .queryForList(anyString(), eq(String.class));
    }

    private void stubSchema(List<String> tables, Map<String, List<String>> columns) {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenReturn(tables);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenAnswer(invocation -> columns.getOrDefault(
                        invocation.getArgument(2, String.class), List.of()));
    }
}
