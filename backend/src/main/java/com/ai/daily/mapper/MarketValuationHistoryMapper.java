package com.ai.daily.mapper;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
