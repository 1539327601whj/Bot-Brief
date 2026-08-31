package com.ai.daily.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V3 内容增长表不在早期 init.sql 里。CREATE TABLE IF NOT EXISTS 各版本都认，
 * 用 information_schema 判断后再建，避免绑定页因缺表 500。
 */
public final class ContentGrowthSchemaRepair {

    static final Map<String, String> CREATE_SQL = new LinkedHashMap<>();

    static {
        CREATE_SQL.put("content_account", """
                CREATE TABLE IF NOT EXISTS content_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    platform VARCHAR(40) NOT NULL,
                    account_name VARCHAR(120) NOT NULL,
                    homepage_url VARCHAR(1000) DEFAULT NULL,
                    avatar_url VARCHAR(1000) DEFAULT NULL,
                    follower_count BIGINT NOT NULL DEFAULT 0,
                    account_positioning VARCHAR(500) DEFAULT NULL,
                    bind_status VARCHAR(40) NOT NULL DEFAULT 'manual',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_content_account_user (user_id),
                    INDEX idx_content_account_user_platform (user_id, platform)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("content_work", """
                CREATE TABLE IF NOT EXISTS content_work (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    account_id BIGINT NOT NULL,
                    platform VARCHAR(40) NOT NULL,
                    title VARCHAR(500) NOT NULL,
                    cover_url VARCHAR(1000) DEFAULT NULL,
                    work_url VARCHAR(1000) DEFAULT NULL,
                    publish_time DATETIME DEFAULT NULL,
                    play_count BIGINT NOT NULL DEFAULT 0,
                    like_count BIGINT NOT NULL DEFAULT 0,
                    comment_count BIGINT NOT NULL DEFAULT 0,
                    collect_count BIGINT NOT NULL DEFAULT 0,
                    share_count BIGINT NOT NULL DEFAULT 0,
                    follower_gain BIGINT NOT NULL DEFAULT 0,
                    content_type VARCHAR(40) NOT NULL DEFAULT 'video',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_content_work_user (user_id),
                    INDEX idx_content_work_account (account_id),
                    INDEX idx_content_work_publish (user_id, publish_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("content_growth_analysis", """
                CREATE TABLE IF NOT EXISTS content_growth_analysis (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    account_id BIGINT DEFAULT NULL,
                    analysis_type VARCHAR(60) NOT NULL,
                    input_text TEXT DEFAULT NULL,
                    result_text MEDIUMTEXT NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_content_analysis_user (user_id, created_at),
                    INDEX idx_content_analysis_account (account_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        CREATE_SQL.put("competitor_account", """
                CREATE TABLE IF NOT EXISTS competitor_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    platform VARCHAR(40) NOT NULL,
                    account_name VARCHAR(120) NOT NULL,
                    homepage_url VARCHAR(1000) DEFAULT NULL,
                    note VARCHAR(500) DEFAULT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_competitor_user (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private ContentGrowthSchemaRepair() {}

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
            throw new IllegalArgumentException("未知内容增长表: " + table);
        }
        return sql;
    }
}
