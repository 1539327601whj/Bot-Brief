package com.ai.daily.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Order(-10)
@RequiredArgsConstructor
public class SubscriptionSchemaRepairRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class,
                SubscriptionSchemaRepair.TABLE);
        if (tables == null || tables == 0) {
            throw new IllegalStateException("缺少 subscription 表，请先执行 backend/sql/init.sql");
        }

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                String.class,
                SubscriptionSchemaRepair.TABLE);
        List<String> alters = SubscriptionSchemaRepair.missingAlters(columns);
        for (String sql : alters) {
            log.warn("补齐订阅表列: {}", sql);
            jdbcTemplate.execute(sql);
        }

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                String.class,
                SubscriptionSchemaRepair.TABLE);
        if (SubscriptionSchemaRepair.needsUniqueIndex(indexes)) {
            try {
                jdbcTemplate.execute(SubscriptionSchemaRepair.createUniqueIndexSql());
                log.info("已创建 {}", SubscriptionSchemaRepair.UNIQUE_INDEX);
            } catch (Exception e) {
                log.warn("无法创建 {}（可能已有重复 user_id）: {}",
                        SubscriptionSchemaRepair.UNIQUE_INDEX, e.getMessage());
            }
        }

        if (!alters.isEmpty()) {
            log.info("订阅表已补齐 {} 列", alters.size());
        }
    }
}
