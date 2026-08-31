package com.ai.daily.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShopAnalyticsSchemaRepairTest {

    @Test
    void missingTablesWhenLegacyInitSqlHasNone() {
        assertThat(ShopAnalyticsSchemaRepair.missingTables(List.of("subscription", "reports")))
                .containsExactly(
                        "shop_store",
                        "shop_product",
                        "shop_sales_daily",
                        "shop_product_sales_daily",
                        "shop_customer_summary",
                        "shop_ai_report");
    }

    @Test
    void missingTablesIgnoresNameCase() {
        assertThat(ShopAnalyticsSchemaRepair.missingTables(ShopAnalyticsSchemaRepair.requiredTables()
                .stream().map(String::toUpperCase).toList())).isEmpty();
    }

    @Test
    void createSqlUsesIfNotExists() {
        for (String table : ShopAnalyticsSchemaRepair.requiredTables()) {
            assertThat(ShopAnalyticsSchemaRepair.createSql(table))
                    .contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }

    @Test
    void productExternalIndexIsDetectedByName() {
        assertThat(ShopAnalyticsSchemaRepair.needsProductExternalIndex(List.of("PRIMARY", "idx_shop_product_name")))
                .isTrue();
        assertThat(ShopAnalyticsSchemaRepair.needsProductExternalIndex(
                List.of("UK_SHOP_PRODUCT_USER_STORE_EXTERNAL"))).isFalse();
        assertThat(ShopAnalyticsSchemaRepair.createProductExternalIndexSql())
                .contains(ShopAnalyticsSchemaRepair.PRODUCT_EXTERNAL_INDEX)
                .contains("shop_product");
    }
}
