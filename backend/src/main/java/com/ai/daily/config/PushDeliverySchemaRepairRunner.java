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
@Order(-11)
@RequiredArgsConstructor
public class PushDeliverySchemaRepairRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('push_channel','push_log')",
                String.class);
        List<String> missing = PushDeliverySchemaRepair.missingTables(existing);
        for (String table : missing) {
            log.warn("补建推送表: {}", table);
            jdbcTemplate.execute(PushDeliverySchemaRepair.createSql(table));
        }
        if (!missing.isEmpty()) {
            log.info("推送表已补齐 {}", missing);
        }

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'push_log'",
                String.class);
        if (PushDeliverySchemaRepair.needsDispatchKey(columns)) {
            log.warn("补列 push_log.dispatch_key");
            jdbcTemplate.execute(PushDeliverySchemaRepair.addDispatchKeySql());
        }

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'push_log'",
                String.class);
        if (PushDeliverySchemaRepair.needsDispatchKeyIndex(indexes)) {
            try {
                jdbcTemplate.execute(PushDeliverySchemaRepair.createDispatchKeyIndexSql());
                log.info("已创建 {}", PushDeliverySchemaRepair.DISPATCH_KEY_INDEX);
            } catch (Exception e) {
                log.warn("创建 {} 失败: {}", PushDeliverySchemaRepair.DISPATCH_KEY_INDEX, e.getMessage());
            }
        }
    }
}
