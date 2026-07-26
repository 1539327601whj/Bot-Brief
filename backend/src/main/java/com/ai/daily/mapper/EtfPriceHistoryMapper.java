package com.ai.daily.mapper;

import com.ai.daily.entity.EtfPriceHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EtfPriceHistoryMapper extends BaseMapper<EtfPriceHistory> {

    @Insert("""
            <script>
            INSERT INTO etf_price_history
                (fund_code, fund_name, trade_date, open_price, high_price, low_price, close_price,
                 adjustment_type, source, fetched_at, created_at, updated_at)
            VALUES
            <foreach collection="histories" item="item" separator=",">
                (#{item.fundCode}, #{item.fundName}, #{item.tradeDate}, #{item.open}, #{item.high},
                 #{item.low}, #{item.close}, #{item.adjustmentType}, #{item.source}, #{item.fetchedAt},
                 #{item.createdAt}, #{item.updatedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                fund_name = VALUES(fund_name),
                open_price = VALUES(open_price),
                high_price = VALUES(high_price),
                low_price = VALUES(low_price),
                close_price = VALUES(close_price),
                fetched_at = VALUES(fetched_at),
                updated_at = VALUES(updated_at)
            </script>
            """)
    int upsertBatch(@Param("histories") List<EtfPriceHistory> histories);
}
