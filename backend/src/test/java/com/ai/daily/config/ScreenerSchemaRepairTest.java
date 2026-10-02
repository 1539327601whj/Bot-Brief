package com.ai.daily.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScreenerSchemaRepairTest {

    @Test
    void missingTablesWhenOnlyTheOldScreenerTablesExist() {
        assertThat(ScreenerSchemaRepair.missingTables(List.of(
                "screener_universe_snapshot", "screener_universe_stock")))
                .containsExactly("screener_scan_history");
    }

    @Test
    void missingTablesIgnoresNameCaseAndPadding() {
        assertThat(ScreenerSchemaRepair.missingTables(List.of(
                "SCREENER_UNIVERSE_SNAPSHOT",
                " screener_universe_stock ",
                "Screener_Scan_History"))).isEmpty();
    }

    @Test
    void createSqlUsesIfNotExists() {
        for (String table : ScreenerSchemaRepair.requiredTables()) {
            assertThat(ScreenerSchemaRepair.createSql(table))
                    .contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }

    @Test
    void createSqlForAnUnknownTableIsRejectedLoudly() {
        // 打错表名时宁可抛，也不能返回 null 让 runner 去 execute(null)
        assertThatThrownBy(() -> ScreenerSchemaRepair.createSql("screener_history"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("screener_history");
    }

    /**
     * 同一段 DDL 现在有**三份**：{@code ScreenerSchemaRepair.CREATE_SQL}、{@code sql/init.sql}、
     * {@code sql/V14__screener_scan_history.sql}。文件头都写着「改一处必须同时改另一处」，
     * 但约定只有被机器盯着才不会烂掉——上次有人改了一份而没改另一份，表现是
     * 「新装的库有索引、升级上来的库没有」，而且要等线上慢查询才看得出来。
     *
     * <p>比较前把空白折叠掉：三份的缩进和换行本来就不必逐字节相同，要一致的是**列与约束**。
     *
     * <p><b>只盯筛选历史这一张表。</b>另外两张预取表（{@code screener_universe_*}）在
     * init.sql 与 V13 之间早就有**纯注释**的措辞差异（列与约束相同），把它们也纳进来等于
     * 顺手重写两大段 DDL，是另一件事；这里不假装覆盖了它们。
     */
    @Test
    void theHistoryDdlInTheSqlFilesMatchesTheStartupRepairSpec() throws IOException {
        String fromSpec = normalize(ScreenerSchemaRepair.createSql("screener_scan_history"));

        for (Path file : List.of(Path.of("sql", "init.sql"),
                Path.of("sql", "V14__screener_scan_history.sql"))) {
            assertThat(readSqlFile(file))
                    .as("%s 里的 screener_scan_history 建表语句与启动补建用的 SQL 不一致", file)
                    .contains(fromSpec);
        }
    }

    /** 也顺便钉住这段 DDL 里前端/实体真正依赖的几件东西：约束与查询用的索引。 */
    @Test
    void theHistoryDdlKeepsTheConstraintsAndIndexTheCodeReliesOn() {
        String sql = normalize(ScreenerSchemaRepair.createSql("screener_scan_history"));

        assertThat(sql).contains("CONSTRAINT chk_screener_history_mode CHECK (mode IN ('index_first','index_only','stock_only'))");
        assertThat(sql).contains("CONSTRAINT chk_screener_history_bucket CHECK (bucket IN ('both','steady','growth'))");
        // 列表按 scanned_at 倒序翻页，没有这条索引就是全表扫 + filesort
        assertThat(sql).contains("INDEX idx_screener_history_scanned_at (scanned_at DESC)");
        // 结果快照是整页回放用的，必须装得下；params_json 一并放宽，免得参数变多变回来
        assertThat(sql).contains("result_json LONGTEXT NOT NULL").contains("params_json LONGTEXT NOT NULL");
        assertThat(sql).contains("selection_json LONGTEXT NOT NULL");
    }

    /** surefire 的工作目录是模块目录（backend/），从仓库根跑时再退一级去找。 */
    private static String readSqlFile(Path relative) throws IOException {
        Path p = relative;
        if (!Files.exists(p)) {
            p = Path.of("backend").resolve(relative);
        }
        assertThat(Files.exists(p)).as("找不到 %s", p).isTrue();
        return normalize(Files.readString(p, StandardCharsets.UTF_8));
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}