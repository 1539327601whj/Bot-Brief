package com.ai.daily.mapper;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface MarketValuationHistoryMapper extends BaseMapper<MarketValuationHistory> {

    @Insert("""
            INSERT INTO market_valuation_history
                (index_code, index_name, pe_ttm, pe_percentile, percentile_method,
                 valuation_level, trade_date, source, created_at)
            VALUES
                (#{indexCode}, #{indexName}, #{peTtm}, #{pePercentile}, #{percentileMethod},
                 #{valuationLevel}, #{tradeDate}, #{source}, #{createdAt})
            ON DUPLICATE KEY UPDATE
                index_name = VALUES(index_name),
                pe_ttm = VALUES(pe_ttm),
                pe_percentile = VALUES(pe_percentile),
                valuation_level = VALUES(valuation_level),
                source = VALUES(source)
            """)
    int upsert(MarketValuationHistory history);

    /** 指数池一天一同步，逐条 upsert 会把 60+ 个指数变成 60+ 次往返。 */
    @Insert("""
            <script>
            INSERT INTO market_valuation_history
                (index_code, index_name, pe_ttm, pe_percentile, percentile_method,
                 valuation_level, trade_date, source, created_at)
            VALUES
            <foreach collection="histories" item="item" separator=",">
                (#{item.indexCode}, #{item.indexName}, #{item.peTtm}, #{item.pePercentile},
                 #{item.percentileMethod}, #{item.valuationLevel}, #{item.tradeDate},
                 #{item.source}, #{item.createdAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                index_name = VALUES(index_name),
                pe_ttm = VALUES(pe_ttm),
                pe_percentile = VALUES(pe_percentile),
                valuation_level = VALUES(valuation_level),
                source = VALUES(source)
            </script>
            """)
    int upsertBatch(@Param("histories") List<MarketValuationHistory> histories);

    /**
     * 每个 {@code (index_code, percentile_method)} 各取最新一行。指数池一天一同步，
     * 逐条 {@code latest()} 会把 60+ 个指数变成 60+ 次往返。
     *
     * <p>分区键是**两列**，不是只有 index_code：同一指数在不同口径下各有各的最新值，
     * 按单列分区会把两个口径混成一行，那正是章程 §6.2 禁止的跨口径比较。
     *
     * <p>用行值 {@code IN ((?, ?), …)} 而不是 {@code OR} 串，是因为 MySQL 8 能对行构造器
     * 走 {@code idx_index_method_trade_date} 的 range 访问，而一长串 {@code OR} 在键多时
     * 会退化成全表扫。
     */
    @Select("""
            <script>
            SELECT * FROM (
                SELECT h.*,
                       ROW_NUMBER() OVER (PARTITION BY index_code, percentile_method
                                          ORDER BY trade_date DESC) AS rn
                FROM market_valuation_history h
                WHERE h.pe_percentile IS NOT NULL
                  AND (h.index_code, h.percentile_method) IN
                  <foreach collection="keys" item="k" open="(" separator="," close=")">
                      (#{k.indexCode}, #{k.percentileMethod})
                  </foreach>
            ) ranked
            WHERE rn = 1
            </script>
            """)
    List<MarketValuationHistory> latestForIndices(
            @Param("keys") List<MarketValuationHistoryService.Key> keys);

    /**
     * 某个 {@code (index_code, percentile_method)} 在一个日期区间内的**全部**分位观测，按交易日升序。
     *
     * <p>「代码查询」页要算 5 年 / 10 年的基线，得把整段历史端点出来自己做回看，
     * 这不是 {@code latest} 能回答的。
     *
     * <p>两个过滤条件都不是可选的：
     * <ul>
     *   <li>{@code percentile_method} 必须匹配——跨口径取数会把两个数拼成一条不存在的曲线
     *       （章程 §5.3：一个指数只钉一个源）。</li>
     *   <li>{@code pe_percentile IS NOT NULL}——只写进 PE 没算出分位的行不能当作
     *       「那天分位的基线」。混进系列里会让回看算出一段假的平线。</li>
     * </ul>
     *
     * <p>走 {@code idx_index_method_trade_date}，一个指数 10 年约 2500 行，
     * 一次点击的量级。**不做分页**：分页会让「10 年基线」落在第二页而静默消失。
     *
     * @param from 含；{@code null} 表示不限下界
     * @param to   含；{@code null} 表示不限上界
     */
    @Select("""
            <script>
            SELECT * FROM market_valuation_history
            WHERE index_code = #{indexCode}
              AND percentile_method = #{percentileMethod}
              AND pe_percentile IS NOT NULL
            <if test="from != null">  AND trade_date &gt;= #{from} </if>
            <if test="to != null">    AND trade_date &lt;= #{to}   </if>
            ORDER BY trade_date ASC
            </script>
            """)
    List<MarketValuationHistory> historyBetween(
            @Param("indexCode") String indexCode,
            @Param("percentileMethod") String percentileMethod,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
