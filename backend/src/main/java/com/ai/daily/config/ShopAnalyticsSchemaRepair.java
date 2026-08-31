package com.ai.daily.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V3 店铺表不在早期 init.sql 里。CREATE TABLE IF NOT EXISTS 各版本都认，
 * 用 information_schema 判断后再建，避免店铺分析页因缺表 500。
 */
public final class ShopAnalyticsSchemaRepair {

    public static final String PRODUCT_EXTERNAL_INDEX = "uk_shop_product_user_store_external";

    static final Map<String, String> CREATE_SQL = new LinkedHashMap<>();

    static {
        CREATE_SQL.put("shop_store", """
                CREATE TABLE IF NOT EXISTS shop_store (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    platform VARCHAR(30) NOT NULL DEFAULT 'manual',
                    store_name VARCHAR(100) NOT NULL,
                    external_store_id VARCHAR(100) DEFAULT NULL,
                    enabled TINYINT(1) NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_shop_store_user_id (user_id),
                    INDEX idx_shop_store_user_platform (user_id, platform)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("shop_product", """
                CREATE TABLE IF NOT EXISTS shop_product (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    store_id BIGINT NOT NULL,
                    platform VARCHAR(30) NOT NULL DEFAULT 'manual',
                    external_product_id VARCHAR(100) DEFAULT NULL,
                    product_name VARCHAR(255) NOT NULL,
                    category VARCHAR(100) DEFAULT NULL,
                    price DECIMAL(12,2) NOT NULL DEFAULT 0,
                    stock INT NOT NULL DEFAULT 0,
                    status VARCHAR(30) NOT NULL DEFAULT 'active',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_shop_product_user_store (user_id, store_id),
                    INDEX idx_shop_product_name (product_name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("shop_sales_daily", """
                CREATE TABLE IF NOT EXISTS shop_sales_daily (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    store_id BIGINT NOT NULL,
                    stat_date DATE NOT NULL,
                    sales_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
                    order_count INT NOT NULL DEFAULT 0,
                    buyer_count INT NOT NULL DEFAULT 0,
                    refund_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_shop_sales_user_store_date (user_id, store_id, stat_date),
                    INDEX idx_shop_sales_user_store (user_id, store_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("shop_product_sales_daily", """
                CREATE TABLE IF NOT EXISTS shop_product_sales_daily (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    store_id BIGINT NOT NULL,
                    product_id BIGINT NOT NULL,
                    stat_date DATE NOT NULL,
                    sales_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
                    order_count INT NOT NULL DEFAULT 0,
                    quantity_sold INT NOT NULL DEFAULT 0,
                    stock INT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_shop_product_sales_user_store_product_date (user_id, store_id, product_id, stat_date),
                    INDEX idx_shop_product_sales_user_store_date (user_id, store_id, stat_date),
                    INDEX idx_shop_product_sales_product (product_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("shop_customer_summary", """
                CREATE TABLE IF NOT EXISTS shop_customer_summary (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    store_id BIGINT NOT NULL,
                    stat_date DATE NOT NULL,
                    new_customer_count INT NOT NULL DEFAULT 0,
                    repeat_customer_count INT NOT NULL DEFAULT 0,
                    high_value_customer_count INT NOT NULL DEFAULT 0,
                    avg_customer_value DECIMAL(14,2) NOT NULL DEFAULT 0,
                    female_ratio DECIMAL(5,2) DEFAULT NULL,
                    male_ratio DECIMAL(5,2) DEFAULT NULL,
                    top_regions JSON DEFAULT NULL,
                    age_distribution JSON DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_shop_customer_user_store_date (user_id, store_id, stat_date),
                    INDEX idx_shop_customer_user_store (user_id, store_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("shop_ai_report", """
                CREATE TABLE IF NOT EXISTS shop_ai_report (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    store_id BIGINT NOT NULL,
                    report_date DATE NOT NULL,
                    title VARCHAR(255) NOT NULL,
                    summary VARCHAR(500) DEFAULT NULL,
                    content LONGTEXT NOT NULL,
                    risk_level VARCHAR(20) NOT NULL DEFAULT 'normal',
                    generated_by VARCHAR(30) NOT NULL DEFAULT 'rule',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_shop_ai_report_user_store_date (user_id, store_id, report_date),
                    INDEX idx_shop_ai_report_user_store (user_id, store_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private ShopAnalyticsSchemaRepair() {
    }

    public static List<String> requiredTables() {
        return List.copyOf(CREATE_SQL.keySet());
    }

    public static List<String> missingTables(Iterable<String> existingTables) {
        Set<String> present = SubscriptionSchemaRepair.normalizeNames(existingTables);
        List<String> missing = new ArrayList<>();
        for (String table : CREATE_SQL.keySet()) {
            if (!present.contains(table.toLowerCase(Locale.ROOT))) {
                missing.add(table);
            }
        }
        return missing;
    }

    public static String createSql(String table) {
        String sql = CREATE_SQL.get(table);
        if (sql == null) {
            throw new IllegalArgumentException("未知店铺分析表: " + table);
        }
        return sql;
    }

    public static boolean needsProductExternalIndex(Iterable<String> existingIndexes) {
        return !SubscriptionSchemaRepair.normalizeNames(existingIndexes)
                .contains(PRODUCT_EXTERNAL_INDEX.toLowerCase(Locale.ROOT));
    }

    public static String createProductExternalIndexSql() {
        return "CREATE UNIQUE INDEX " + PRODUCT_EXTERNAL_INDEX
                + " ON shop_product(user_id, store_id, external_product_id)";
    }
}
