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
@Order(-8)
@RequiredArgsConstructor
public class ShopAnalyticsSchemaRepairRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN "
                        + "('shop_store','shop_product','shop_sales_daily','shop_product_sales_daily',"
                        + "'shop_customer_summary','shop_ai_report')",
                String.class);
        List<String> missing = ShopAnalyticsSchemaRepair.missingTables(existing);
        for (String table : missing) {
            log.warn("补建店铺分析表: {}", table);
            jdbcTemplate.execute(ShopAnalyticsSchemaRepair.createSql(table));
        }
        if (!missing.isEmpty()) {
            log.info("店铺分析表已补齐 {}", missing);
        }
        ensureProductExternalIndex();
    }

    private void ensureProductExternalIndex() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop_product'",
                String.class);
        if (!ShopAnalyticsSchemaRepair.needsProductExternalIndex(indexes)) {
            return;
        }
        try {
            log.warn("补建店铺商品外部 ID 唯一索引");
            jdbcTemplate.execute(ShopAnalyticsSchemaRepair.createProductExternalIndexSql());
        } catch (Exception error) {
            log.error("店铺商品外部 ID 唯一索引未建上，导入可能无法幂等覆盖: {}", error.getMessage());
        }
    }
}
