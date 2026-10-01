package com.ai.daily.mapper;

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

    private static MybatisConfiguration parseAll() {
        MybatisConfiguration config = new MybatisConfiguration();
        new MybatisMapperAnnotationBuilder(config, MarketValuationHistoryMapper.class).parse();
        new MybatisMapperAnnotationBuilder(config, EtfPriceHistoryMapper.class).parse();
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