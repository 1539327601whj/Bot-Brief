package com.ai.daily.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 旧库常缺 V2/V4 订阅列；生产 MySQL 又不认 ADD COLUMN IF NOT EXISTS。
 * 用 information_schema 判断后再发普通 ALTER。
 */
public final class SubscriptionSchemaRepair {

    public static final String TABLE = "subscription";
    public static final String UNIQUE_INDEX = "uk_subscription_user_id";

    public record ColumnPatch(String name, String definition) {
        String alterSql() {
            return "ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + definition;
        }
    }

    static final List<ColumnPatch> REQUIRED_COLUMNS = List.of(
            new ColumnPatch("user_id", "BIGINT NOT NULL DEFAULT 1"),
            new ColumnPatch("morning_enabled", "TINYINT(1) NOT NULL DEFAULT 1"),
            new ColumnPatch("morning_time", "TIME NOT NULL DEFAULT '08:00:00'"),
            new ColumnPatch("evening_enabled", "TINYINT(1) NOT NULL DEFAULT 1"),
            new ColumnPatch("evening_time", "TIME NOT NULL DEFAULT '20:00:00'"),
            new ColumnPatch("topic_schedules", "JSON DEFAULT NULL")
    );

    private SubscriptionSchemaRepair() {}

    public static Set<String> normalizeNames(Iterable<String> names) {
        Set<String> normalized = new LinkedHashSet<>();
        if (names == null) {
            return normalized;
        }
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    public static List<String> missingAlters(Iterable<String> existingColumns) {
        Set<String> present = normalizeNames(existingColumns);
        List<String> alters = new ArrayList<>();
        for (ColumnPatch patch : REQUIRED_COLUMNS) {
            if (!present.contains(patch.name().toLowerCase(Locale.ROOT))) {
                alters.add(patch.alterSql());
            }
        }
        return alters;
    }

    public static boolean needsUniqueIndex(Iterable<String> existingIndexes) {
        return !normalizeNames(existingIndexes).contains(UNIQUE_INDEX.toLowerCase(Locale.ROOT));
    }

    public static String createUniqueIndexSql() {
        return "CREATE UNIQUE INDEX " + UNIQUE_INDEX + " ON " + TABLE + "(user_id)";
    }
}
