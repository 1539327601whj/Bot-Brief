package com.ai.daily.mapper;

import com.ai.daily.entity.ScreenerUniverseStock;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperAnnotationBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量读那两条 SQL 的**文本形状**。
 *
 * <p>为什么要专门测这个：注解里的 SQL 用 {@code <script>} 包着，{@code <foreach>} 与
 * {@code &gt;=} 都由 MyBatis 在启动时解析。写错了的后果不是「查不到」，而是
 * **启动即失败**或者**运行到那一行才抛**——而这两条恰恰是本地没有 MySQL 时最容易漏测的部分
 * （单测里 mapper 全是 mock，SQL 文本根本没被解析过）。
 *
 * <p>这里用的是 MP 启动时真正用的那个 builder，所以「测过」与「跑起来」是同一套解析逻辑。
 * 它**不**校验 MySQL 语法（那需要真的连库），能钉住的是：XML 转义、{@code <foreach>} 展开、
 * 占位符个数，以及分区键是不是**两列**。
 */
class MapperSqlShapeTest {

    private static final String VALUATION = "com.ai.daily.mapper.MarketValuationHistoryMapper.latestForIndices";
    private static final String PRICES = "com.ai.daily.mapper.EtfPriceHistoryMapper.latestBatch";
    private static final String PREFETCH_STOCKS = "com.ai.daily.mapper.ScreenerUniverseStockMapper.upsertBatch";
    private static final String PREFETCH_STOCKS_READ = "com.ai.daily.mapper.ScreenerUniverseStockMapper.findByTradeDate";
    private static final String PREFETCH_HEADER = "com.ai.daily.mapper.ScreenerUniverseSnapshotMapper.upsert";

    /** 明细表 28 列、头表 10 列；下面两条测试按它们算占位符个数。改表结构必改这里。 */
    private static final int PREFETCH_STOCK_COLUMNS = 28;
    private static final int PREFETCH_STOCK_UPDATED = 25;

    private static MybatisConfiguration parseAll() {
        MybatisConfiguration config = new MybatisConfiguration();
        new MybatisMapperAnnotationBuilder(config, MarketValuationHistoryMapper.class).parse();
        new MybatisMapperAnnotationBuilder(config, EtfPriceHistoryMapper.class).parse();
        new MybatisMapperAnnotationBuilder(config, ScreenerUniverseStockMapper.class).parse();
        new MybatisMapperAnnotationBuilder(config, ScreenerUniverseSnapshotMapper.class).parse();
        return config;
    }

    private static BoundSql boundSql(MybatisConfiguration config, String id, Object params) {
        MappedStatement ms = config.getMappedStatement(id);
        assertThat(ms).as("语句 " + id + " 应当被解析出来").isNotNull();
        return ms.getBoundSql(params);
    }

    /**
     * 把 MyBatis 拼出来的文本压成一种稳定形状再比对：换行、缩进、括号内外的空格都由
     * 标签里源码的排版决定，比对它们只会让测试因为「改了缩进」而红，与 SQL 对不对无关。
     */
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replace("( ", "(").replace(" )", ")")
                .replace(" , ", ", ")
                .trim();
    }

    @Test
    void thePriceBatchQueryExpandsEveryCodeAndKeepsTheDateLowerBound() {
        MybatisConfiguration config = parseAll();

        String sql = boundSql(config, PRICES, Map.of(
                "fundCodes", List.of("510300", "510500", "159915"),
                "from", LocalDate.of(2025, 5, 20),
                "adjustmentType", "QFQ")).getSql();

        String flat = normalize(sql);
        assertThat(flat).contains("FROM etf_price_history");
        // `>` 在 XML 里不转义也能过，但一旦有人写成 `&lt;`，比较方向会静默反过来：
        // 「取近一年」变成「取一年以前那些」
        assertThat(flat).contains("trade_date >= ?");
        assertThat(flat).contains("adjustment_type = ?");
        assertThat(flat).contains("fund_code IN (?, ?, ?)");
        assertThat(flat).contains("ORDER BY fund_code, trade_date");
        // 5 个占位符 = 复权口径 1 + 日期下界 1 + 三个代码。多一个就说明有人改了写法没改这里
        assertThat(countOf(flat, "?")).isEqualTo(5);
    }

    @Test
    void theValuationQueryPartitionsByBothIndexAndMethod() {
        MybatisConfiguration config = parseAll();

        String sql = boundSql(config, VALUATION, Map.of("keys", List.of(
                new MarketValuationHistoryService.Key("SH000300", "CSI_PE_TTM_ROLLING_10Y"),
                new MarketValuationHistoryService.Key("SZ399006", "DANJUAN_PE_TTM_PROVIDER")))).getSql();

        String flat = normalize(sql).toLowerCase();
        // 分区键必须是**两列**：只按 index_code 分区会把同一指数的两个口径混成一行，
        // 那正是章程 §6.2 禁止的跨口径比较，而结果看上去完全正常
        assertThat(flat).contains("row_number() over (partition by index_code, percentile_method");
        assertThat(flat).contains("order by trade_date desc");
        assertThat(flat).contains("where rn = 1");
        // 没分位的行不算「这个指数有分位」
        assertThat(flat).contains("pe_percentile is not null");
        // 行值 IN：换成 OR 串在键多时会退化成全表扫
        assertThat(flat).contains("(h.index_code, h.percentile_method) in ((?, ?), (?, ?))");
        // 派生表必须有别名，否则 MySQL 直接报错
        assertThat(flat).contains(") ranked");
    }

    @Test
    void aSingleKeyStillProducesValidSql() {
        MybatisConfiguration config = parseAll();

        String flat = normalize(boundSql(config, VALUATION, Map.of("keys", List.of(
                new MarketValuationHistoryService.Key("SH000300", "CSI_PE_TTM_ROLLING_10Y"))))
                .getSql()).toLowerCase();

        // 只有一个键时也得是合法的行值 IN，而不是空括号
        assertThat(flat).contains("in ((?, ?))");
        assertThat(countOf(flat, "?")).isEqualTo(2);
    }

    // ================= 盘后预取那两条写入语句 =================

    /**
     * 明细的批量 upsert。要钉住的是**跑第二遍不出错也不留旧值**。
     *
     * <p>这里最容易出的错不是语法，是「往 INSERT 里加了一列，忘了往 {@code ON DUPLICATE KEY UPDATE}
     * 里也加」：重跑时会静默保留上一遍的值，页面上的基本面数据据此就与实际的交易日错位了，
     * 而且不会有任何报错。所以下面直接钉住列数与更新条目数。
     */
    @Test
    void thePrefetchDetailBatchExpandsEveryRowAndOverwritesEveryNonKeyColumn() {
        MybatisConfiguration config = parseAll();

        String flat = normalize(boundSql(config, PREFETCH_STOCKS,
                Map.of("rows", List.of(stock("600519"), stock("000001"), stock("510300")))).getSql());

        assertThat(flat).contains("INSERT INTO screener_universe_stock");
        assertThat(flat).contains("ON DUPLICATE KEY UPDATE");
        // 三行 × 28 列
        assertThat(countOf(flat, "?")).isEqualTo(PREFETCH_STOCK_COLUMNS * 3);
        // 每行一个 VALUES(...) 元组，加 25 条 `= VALUES(...)` 的覆盖
        assertThat(countOf(flat, " = VALUES(")).isEqualTo(PREFETCH_STOCK_UPDATED);
        // is_etf 是唯一一个实体字段名与列名不同名的（etf / is_etf），单独钉一下，
        // 免得将来有人「顺手统一命名」时两边只改一处
        assertThat(flat).contains("is_etf = VALUES(is_etf)");
    }

    /**
     * 头表的 upsert。唯一键是 {@code trade_date}，所以重跑只覆盖同一行。
     *
     * <p>{@code prefetch_date} 必须在更新列里：它才是「今天跑过没有」那道闸门读的列。
     * 漏掉它，跨自然日重跑（比如周一补跑上周五的数据）时闸门会一直判否。
     */
    @Test
    void thePrefetchHeaderUpsertOverwritesTheWriteGateColumn() {
        MybatisConfiguration config = parseAll();

        String flat = normalize(boundSql(config, PREFETCH_HEADER,
                Map.of("s", new com.ai.daily.entity.ScreenerUniverseSnapshot())).getSql());

        assertThat(flat).contains("INSERT INTO screener_universe_snapshot");
        assertThat(flat).contains("ON DUPLICATE KEY UPDATE");
        // 唯一键那一列必须还在第一个位置：ON DUPLICATE KEY 认的就是它的索引
        assertThat(flat).contains("(trade_date, prefetch_date");
        assertThat(flat).contains("prefetch_date = VALUES(prefetch_date)");
        // 键列自己要能被覆盖就说明有人把唯一键改成别的了
        assertThat(flat).doesNotContain("trade_date = VALUES");
        assertThat(countOf(flat, "?")).isEqualTo(10);
    }

    /**
     * 明细的读语句必须把 {@code is_etf} 别名成 {@code etf}。
     *
     * <p>实体字段叫 {@code etf}、列叫 {@code is_etf}，MyBatis 按列名推属性名时找的是
     * {@code isEtf}——找不到，而且**不报错，只是把那列丢掉**，读回来恒为 false。
     * 这条测试就是钉住那个别名，以及「别改回 {@code SELECT *}」。
     */
    @Test
    void thePrefetchDetailReadAliasesTheEtfColumnSoItIsNotSilentlyDropped() {
        MybatisConfiguration config = parseAll();

        String flat = normalize(boundSql(config, PREFETCH_STOCKS_READ,
                Map.of("tradeDate", LocalDate.of(2026, 9, 30))).getSql());

        assertThat(flat).contains("is_etf AS etf");
        assertThat(flat).doesNotContain("SELECT *");
        assertThat(flat).contains("WHERE snapshot_trade_date = ?");
        assertThat(flat).contains("ORDER BY stock_code");
        // 实体上那 28 列的映射就此固定；漏掉任何一列都会让读回来的对象悄悄少一个字段
        // 列数固定：INSERT 那 28 列 + 主键 id = 29 列 → 28 个逗号。
        // 漏掉一列不会报错，只会让读回来的对象少一个字段（变 null），
        // 于是筛选规则悄悄换了个样子而页面上看不出来
        assertThat(countOf(flat, ",")).isEqualTo(29 - 1);
    }

    private static ScreenerUniverseStock stock(String code) {
        ScreenerUniverseStock s = new ScreenerUniverseStock();
        s.setSnapshotTradeDate(LocalDate.of(2026, 9, 30));
        s.setStockCode(code);
        s.setMarket(1);
        return s;
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}