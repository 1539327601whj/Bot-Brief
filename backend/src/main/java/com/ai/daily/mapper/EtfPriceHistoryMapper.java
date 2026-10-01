package com.ai.daily.mapper;

import com.ai.daily.entity.EtfPriceHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
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

    /**
     * 一次读回一批基金的前复权日线。**这是「点击时零外呼」的主力**：
     * 日更预取把日线写进库，点击时一次查询取回全部指数池 ETF 的曲线。
     *
     * <p>写入侧的 {@code latest(fundCode, …)} 是**每份基金一次查询**，
     * 池子到 200 条时就是 200 次往返——这正是本方法存在的理由。
     *
     * <p>用 {@code trade_date >= from} 这个日期下界，是为了吃到
     * {@code idx_etf_price_latest (fund_code, adjustment_type, trade_date DESC)} 的前缀，
     * 而**不是**写窗口函数。返回的是**混源**的多行（唯一键含 source，同一天可能两家都有），
     * 调用方必须用 {@link com.ai.daily.screener.EtfPriceSeriesSelector} 先选出一条同源序列再用。
     */
    @Select("""
            <script>
            SELECT * FROM etf_price_history
            WHERE adjustment_type = #{adjustmentType}
              AND trade_date &gt;= #{from}
              AND fund_code IN
              <foreach collection="fundCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
            ORDER BY fund_code, trade_date
            </script>
            """)
    List<EtfPriceHistory> latestBatch(@Param("fundCodes") List<String> fundCodes,
                                     @Param("from") LocalDate from,
                                     @Param("adjustmentType") String adjustmentType);
}
