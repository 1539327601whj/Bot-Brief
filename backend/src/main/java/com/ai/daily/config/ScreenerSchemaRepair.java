package com.ai.daily.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「低估精选」的两张预取表与新加的筛选历史表。
 *
 * <p>前两张原本只写在 {@code backend/sql/init.sql} 与 {@code V13__screener_prefetch.sql} 里，
 * 靠人工执行；这一轮起改成**启动时补建**，理由是 {@code scripts/release-backend.sh} 自己的约定：
 * 只有 V10–V12 那几张要人工跑（脚本会在发布前查它们、缺了就中止），其余新表都是
 * *repaired on backend startup*。历史表若也走人工执行，发布脚本不会拦，**部署会成功、
 * 点一次筛选才在运行时炸**——那正是这套 repair 要消掉的失败模式。
 *
 * <p>SQL 文本与 {@code init.sql} / {@code V14__screener_scan_history.sql} 必须一致，改一处要同时改。
 */
public final class ScreenerSchemaRepair {

    static final Map<String, String> CREATE_SQL = new LinkedHashMap<>();

    static {
        CREATE_SQL.put("screener_universe_snapshot", """
                CREATE TABLE IF NOT EXISTS screener_universe_snapshot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    trade_date DATE NOT NULL COMMENT '清单所属交易日',
                    prefetch_date DATE NOT NULL COMMENT '预取执行的自然日',
                    pool_size INT NOT NULL COMMENT '本次请求的池子大小 N',
                    listed_count INT NOT NULL COMMENT '清单取到的条数',
                    missing_count INT NOT NULL COMMENT '清单有、行情没取到、已剔除的条数',
                    cap_floor DECIMAL(24, 4) DEFAULT NULL COMMENT '池内最小总市值（元）',
                    source VARCHAR(100) NOT NULL COMMENT '数据来源',
                    fetched_at DATETIME NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT chk_screener_universe_counts CHECK (pool_size > 0 AND listed_count >= 0 AND missing_count >= 0),
                    CONSTRAINT chk_screener_universe_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
                    UNIQUE KEY uk_screener_universe_trade_date (trade_date),
                    INDEX idx_screener_universe_prefetch_date (prefetch_date),
                    INDEX idx_screener_universe_fetched_at (fetched_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选全市场快照头'
                """);
        CREATE_SQL.put("screener_universe_stock", """
                CREATE TABLE IF NOT EXISTS screener_universe_stock (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    snapshot_trade_date DATE NOT NULL,
                    stock_code VARCHAR(16) NOT NULL,
                    stock_name VARCHAR(100) DEFAULT NULL,
                    market TINYINT NOT NULL,
                    is_etf TINYINT NOT NULL DEFAULT 0,
                    industry VARCHAR(100) DEFAULT NULL,
                    price DECIMAL(18, 6) DEFAULT NULL,
                    pct_change DECIMAL(12, 4) DEFAULT NULL,
                    amount DECIMAL(24, 4) DEFAULT NULL,
                    turnover_rate DECIMAL(12, 4) DEFAULT NULL,
                    total_market_cap DECIMAL(24, 4) DEFAULT NULL,
                    float_market_cap DECIMAL(24, 4) DEFAULT NULL,
                    pb DECIMAL(18, 6) DEFAULT NULL,
                    pe_ttm DECIMAL(18, 6) DEFAULT NULL,
                    roe DECIMAL(12, 4) DEFAULT NULL,
                    revenue_growth DECIMAL(12, 4) DEFAULT NULL,
                    profit_growth DECIMAL(12, 4) DEFAULT NULL,
                    gross_margin DECIMAL(12, 4) DEFAULT NULL,
                    debt_ratio DECIMAL(12, 4) DEFAULT NULL,
                    dividend_yield DECIMAL(12, 4) DEFAULT NULL,
                    bps DECIMAL(18, 6) DEFAULT NULL,
                    list_date DATE DEFAULT NULL,
                    change_60d DECIMAL(12, 4) DEFAULT NULL,
                    ytd_change DECIMAL(12, 4) DEFAULT NULL,
                    source VARCHAR(100) NOT NULL,
                    fetched_at DATETIME NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    CONSTRAINT chk_screener_stock_market CHECK (market IN (0, 1)),
                    CONSTRAINT chk_screener_stock_source_not_blank CHECK (CHAR_LENGTH(TRIM(source)) > 0),
                    UNIQUE KEY uk_screener_universe_stock (snapshot_trade_date, stock_code),
                    INDEX idx_screener_universe_stock_code (stock_code, snapshot_trade_date DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选全市场快照明细'
                """);
        CREATE_SQL.put("screener_scan_history", """
                CREATE TABLE IF NOT EXISTS screener_scan_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    scanned_at DATETIME NOT NULL COMMENT '本次计算时间（Asia/Shanghai）',
                    scanned_by_user_id BIGINT DEFAULT NULL COMMENT '触发筛选的用户 ID',
                    scanned_by_email VARCHAR(255) DEFAULT NULL COMMENT '触发人展示用',
                    mode VARCHAR(20) NOT NULL COMMENT 'index_first / index_only / stock_only',
                    bucket VARCHAR(20) NOT NULL COMMENT 'both / steady / growth',
                    per_bucket INT NOT NULL COMMENT '每档输出只数',
                    params_json LONGTEXT NOT NULL COMMENT '生效参数快照（解析默认值之后，回填条件面板用）',
                    scanned_count INT NOT NULL COMMENT '扫描总数',
                    after_vetoes INT NOT NULL COMMENT '通过排雷数',
                    steady_pool INT NOT NULL COMMENT '稳健档池子',
                    growth_pool INT NOT NULL COMMENT '成长档池子',
                    shortlist_fetched INT NOT NULL COMMENT '抓取日线只数',
                    index_count INT NOT NULL COMMENT '指数池条目数',
                    qualified_index_count INT NOT NULL COMMENT '其中本次入选（合格）的指数数',
                    selection_json LONGTEXT NOT NULL COMMENT '入选清单：指数/稳健/成长的代码与名称',
                    result_json LONGTEXT NOT NULL COMMENT '完整结果快照，可整页恢复',
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT chk_screener_history_mode CHECK (mode IN ('index_first','index_only','stock_only')),
                    CONSTRAINT chk_screener_history_bucket CHECK (bucket IN ('both','steady','growth')),
                    INDEX idx_screener_history_scanned_at (scanned_at DESC)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='低估精选筛选历史'
                """);
    }

    private ScreenerSchemaRepair() {}

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
            throw new IllegalArgumentException("未知低估精选表: " + table);
        }
        return sql;
    }
}