package com.ai.daily.mapper;

import com.ai.daily.entity.MarketValuationHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

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
}
