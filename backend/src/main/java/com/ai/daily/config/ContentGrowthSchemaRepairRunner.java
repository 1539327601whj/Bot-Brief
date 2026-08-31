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
@Order(-9)
@RequiredArgsConstructor
public class ContentGrowthSchemaRepairRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN "
                        + "('content_account','content_work','content_growth_analysis','competitor_account')",
                String.class);
        List<String> missing = ContentGrowthSchemaRepair.missingTables(existing);
        for (String table : missing) {
            log.warn("补建内容增长表: {}", table);
            jdbcTemplate.execute(ContentGrowthSchemaRepair.createSql(table));
        }
        if (!missing.isEmpty()) {
            log.info("内容增长表已补齐 {}", missing);
        }
    }
}
