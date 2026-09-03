package com.ai.daily.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V2 的 push_channel / push_log 不在早期 init.sql 里。
 * 缺表时测试推送能发出去，但首页「今日投递」一直是 0。
 */
public final class PushDeliverySchemaRepair {

    public static final String PUSH_LOG = "push_log";
    public static final String DISPATCH_KEY = "dispatch_key";
    public static final String DISPATCH_KEY_INDEX = "uk_push_log_dispatch_key";

    static final Map<String, String> CREATE_SQL = new LinkedHashMap<>();

    static {
        CREATE_SQL.put("push_channel", """
                CREATE TABLE IF NOT EXISTS push_channel (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    channel_type VARCHAR(20) NOT NULL COMMENT 'email|wechat|dingtalk|feishu',
                    display_name VARCHAR(100) DEFAULT NULL,
                    target VARCHAR(2500) NOT NULL,
                    secret VARCHAR(1000) DEFAULT NULL,
                    enabled TINYINT(1) NOT NULL DEFAULT 1,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_user_id (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put(PUSH_LOG, """
                CREATE TABLE IF NOT EXISTS push_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    report_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    channel_type VARCHAR(20) NOT NULL,
                    status VARCHAR(20) NOT NULL COMMENT 'success|failed|sending',
                    error_message VARCHAR(1000) DEFAULT NULL,
                    dispatch_key VARCHAR(191) DEFAULT NULL,
                    pushed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_user_pushed (user_id, pushed_at),
                    UNIQUE KEY uk_push_log_dispatch_key (dispatch_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private PushDeliverySchemaRepair() {
    }

    public static List<String> requiredTables() {
        return List.copyOf(CREATE_SQL.keySet());
    }

    public static String createSql(String table) {
        String sql = CREATE_SQL.get(table);
        if (sql == null) {
            throw new IllegalArgumentException("未知推送表: " + table);
        }
        return sql;
    }

    public static List<String> missingTables(Iterable<String> existingTables) {
        Set<String> present = SubscriptionSchemaRepair.normalizeNames(existingTables);
        List<String> missing = new ArrayList<>();
        for (String table : requiredTables()) {
            if (!present.contains(table.toLowerCase(Locale.ROOT))) {
                missing.add(table);
            }
        }
        return missing;
    }

    public static boolean needsDispatchKey(Iterable<String> existingColumns) {
        return !SubscriptionSchemaRepair.normalizeNames(existingColumns).contains(DISPATCH_KEY);
    }

    public static String addDispatchKeySql() {
        return "ALTER TABLE " + PUSH_LOG + " ADD COLUMN " + DISPATCH_KEY + " VARCHAR(191) NULL AFTER error_message";
    }

    public static boolean needsDispatchKeyIndex(Iterable<String> existingIndexes) {
        return !SubscriptionSchemaRepair.normalizeNames(existingIndexes).contains(DISPATCH_KEY_INDEX);
    }

    public static String createDispatchKeyIndexSql() {
        return "CREATE UNIQUE INDEX " + DISPATCH_KEY_INDEX + " ON " + PUSH_LOG + "(" + DISPATCH_KEY + ")";
    }
}
