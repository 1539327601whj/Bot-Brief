package com.ai.daily.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时补建「低估精选」的表：两张盘后预取表 + 筛选历史表。
 *
 * <p>{@code @Order(-7)}：在其它 schema repair（-8…-11）之后、任何业务 Runner 之前跑完。
 * 预取任务（{@code ScreenerPrefetchTask}）没有 {@code @Order}，排在最后，
 * 所以它读到的一定是补建之后的表。
 */
@Slf4j
@Component
@Order(-7)
@RequiredArgsConstructor
public class ScreenerSchemaRepairRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN "
                        + "('screener_universe_snapshot','screener_universe_stock','screener_scan_history')",
                String.class);
        List<String> missing = ScreenerSchemaRepair.missingTables(existing);
        for (String table : missing) {
            log.warn("补建低估精选表: {}", table);
            jdbcTemplate.execute(ScreenerSchemaRepair.createSql(table));
        }
        if (!missing.isEmpty()) {
            log.info("低估精选表已补齐 {}", missing);
        }
    }
}