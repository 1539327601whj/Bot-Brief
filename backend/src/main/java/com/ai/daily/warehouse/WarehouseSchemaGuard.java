package com.ai.daily.warehouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 启动时检查代码查询 v2 的数据底座是否齐全。
 *
 * <p><b>只检查，不自动建表/改列。</b>这是与 {@code ScreenerSchemaRepairRunner} 刻意的差别：
 * 那些表是「缺了就该补」的附属表；这里的表有 14 张、带外键语义与唯一键，
 * 还包含对已有 {@code market_valuation_history} 的 ALTER。启动时静默建一半，
 * 出了问题更难查——而部署脚本与 V15 迁移是可审计的。
 *
 * <p>缺表时**不让应用起不来**：v1 与其它功能必须继续可用。这里只把
 * {@link #ready} 置 false，v2 controller 据此返回 503 和一句能直接照做的提示。
 *
 * <p>列清单必须与 {@code automation/warehouse/schema_manifest.json}、
 * {@code backend/sql/V15__investment_warehouse.sql}、{@code backend/sql/init.sql} 一致。
 * 改表时四处一起改；不一致的后果是「启动看着正常，第一次查询才炸」。
 */
@Slf4j
@Component
@Order(-6)
@RequiredArgsConstructor
public class WarehouseSchemaGuard implements ApplicationRunner {

    public static final String MIGRATION_FILE = "backend/sql/V15__investment_warehouse.sql";

    /** 表 → 必需列。顺序只影响日志，不影响判定。 */
    static final Map<String, List<String>> REQUIRED = required();

    private final JdbcTemplate jdbcTemplate;

    private volatile boolean checked;
    private volatile boolean ready;
    private volatile List<String> missingTables = List.of();
    private volatile Map<String, List<String>> missingColumns = Map.of();
    private volatile String unavailableReason;

    private static Map<String, List<String>> required() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("market_valuation_history", List.of(
                "index_code", "index_name", "pe_ttm", "pe_percentile", "pb",
                "dividend_yield", "percentile_method", "trade_date", "source"));
        map.put("md_instrument", List.of(
                "instrument_code", "asset_type", "market", "raw_code", "name", "aliases",
                "industry", "industry_code", "tracking_index_code", "list_date", "status",
                "source", "fetched_at"));
        map.put("md_price_daily", List.of(
                "instrument_code", "trade_date", "adj_type", "open_price", "high_price",
                "low_price", "close_price", "volume", "amount", "turnover_rate",
                "pct_change", "source", "fetched_at"));
        map.put("md_valuation_daily", List.of(
                "instrument_code", "trade_date", "pe_ttm", "pb_mrq", "ps_ttm",
                "dividend_yield", "close_price", "percentile_method", "source", "fetched_at"));
        map.put("md_financial_report", List.of(
                "stock_code", "report_date", "report_type", "announce_date", "available_from",
                "revenue", "revenue_yoy", "net_profit", "net_profit_yoy", "roe",
                "gross_margin", "net_margin", "ocf", "total_assets", "total_equity",
                "total_liabilities", "current_assets", "current_liabilities",
                "interest_expense", "ebit", "goodwill", "accounts_receivable",
                "source", "fetched_at"));
        map.put("md_moneyflow_daily", List.of(
                "stock_code", "trade_date", "main_net", "super_net", "large_net",
                "medium_net", "small_net", "main_ratio", "source", "fetched_at"));
        map.put("md_index_profile", List.of(
                "index_code", "as_of", "name", "constituent_count", "top10_weight",
                "pe_ttm", "dividend_yield", "total_market_cap", "tracking_index_code",
                "source", "fetched_at"));
        map.put("md_index_industry_weight", List.of(
                "index_code", "as_of", "industry_code", "industry_name", "weight",
                "source", "fetched_at"));
        map.put("md_index_constituent", List.of(
                "index_code", "as_of", "stock_code", "stock_name", "weight",
                "source", "fetched_at"));
        map.put("md_industry_board", List.of(
                "board_code", "name", "source", "fetched_at"));
        map.put("md_industry_constituent", List.of(
                "board_code", "stock_code", "stock_name", "as_of", "source", "fetched_at"));
        map.put("md_event", List.of(
                "instrument_code", "category", "event_type", "title", "summary", "url",
                "published_at", "source", "importance", "polarity", "content_hash", "fetched_at"));
        map.put("md_analysis_cache", List.of(
                "instrument_code", "snapshot_hash", "rules_version", "prompt_version",
                "payload_json", "created_at", "expires_at"));
        map.put("md_sync_job", List.of(
                "job_key", "task", "status", "priority", "instrument_code", "payload_json",
                "cursor", "rows_affected", "error", "retry_after", "started_at", "finished_at"));
        map.put("md_source_state", List.of(
                "provider", "next_allowed_at", "cooldown_until", "last_error", "updated_at"));
        return Map.copyOf(map);
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    /** 幂等；测试与健康检查可以重复调用。 */
    public synchronized void check() {
        if (checked) {
            return;
        }
        checked = true;
        try {
            List<String> existingTables = jdbcTemplate.queryForList(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                    String.class);
            Set<String> tables = new LinkedHashSet<>();
            for (String table : existingTables) {
                if (table != null) {
                    tables.add(table.toLowerCase(Locale.ROOT));
                }
            }

            List<String> missing = new ArrayList<>();
            for (String table : REQUIRED.keySet()) {
                if (!tables.contains(table.toLowerCase(Locale.ROOT))) {
                    missing.add(table);
                }
            }
            missingTables = List.copyOf(missing);

            Map<String, List<String>> missingCols = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : REQUIRED.entrySet()) {
                if (!tables.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                List<String> existingCols = jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                        String.class, entry.getKey());
                Set<String> cols = new LinkedHashSet<>();
                for (String col : existingCols) {
                    if (col != null) {
                        cols.add(col.toLowerCase(Locale.ROOT));
                    }
                }
                List<String> absent = entry.getValue().stream()
                        .filter(required -> !cols.contains(required.toLowerCase(Locale.ROOT)))
                        .toList();
                if (!absent.isEmpty()) {
                    missingCols.put(entry.getKey(), absent);
                }
            }
            missingColumns = Map.copyOf(missingCols);

            ready = missingTables.isEmpty() && missingColumns.isEmpty();
            unavailableReason = ready ? null : buildReason();
            if (ready) {
                log.info("代码查询 v2 数据底座检查通过：{} 张表", REQUIRED.size());
            } else {
                log.error("代码查询 v2 数据底座不完整，v2 端点将返回 503（v1 不受影响）。"
                        + " 请执行 {}。缺失表={} 缺失列={}",
                        MIGRATION_FILE, missingTables, missingColumns);
            }
        } catch (RuntimeException e) {
            ready = false;
            unavailableReason = "检查数据底座失败：" + e.getMessage();
            log.error("检查代码查询 v2 数据底座失败，v2 端点将返回 503", e);
        }
    }

    private String buildReason() {
        StringBuilder message = new StringBuilder("代码查询 v2 的数据底座未就绪，请先在 MySQL 执行 ")
                .append(MIGRATION_FILE)
                .append("。");
        if (!missingTables.isEmpty()) {
            message.append(" 缺失表：").append(String.join(", ", missingTables)).append("。");
        }
        if (!missingColumns.isEmpty()) {
            message.append(" 缺失列：");
            List<String> parts = new ArrayList<>();
            missingColumns.forEach((table, cols) ->
                    parts.add(table + "(" + String.join("/", cols) + ")"));
            message.append(String.join(", ", parts)).append("。");
        }
        return message.toString();
    }

    public boolean isReady() {
        check();
        return ready;
    }

    public List<String> missingTables() {
        check();
        return missingTables;
    }

    public Map<String, List<String>> missingColumns() {
        check();
        return missingColumns;
    }

    public String unavailableReason() {
        check();
        return unavailableReason;
    }
}
