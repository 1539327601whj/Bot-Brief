package com.ai.daily.mapper;

import com.ai.daily.entity.ScreenerUniverseStock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScreenerUniverseStockMapper extends BaseMapper<ScreenerUniverseStock> {

    /**
     * 批量写明细。唯一键 {@code (snapshot_trade_date, stock_code)} 让重跑只覆盖同键行。
     *
     * <p>字段多、每批几百行，所以不做「先查有没有再决定写不写」——那是每行一次往返，
     * 而 {@code ON DUPLICATE KEY UPDATE} 一次就够。代价是即使数据没变也会写一遍，
     * 但这一批是**一天一次**的量，不值得为它加复杂度。
     */
    @Insert("""
            <script>
            INSERT INTO screener_universe_stock
                (snapshot_trade_date, stock_code, stock_name, market, is_etf, industry,
                 price, pct_change, amount, turnover_rate, total_market_cap, float_market_cap,
                 pb, pe_ttm, roe, revenue_growth, profit_growth, gross_margin, debt_ratio,
                 dividend_yield, bps, list_date, change_60d, ytd_change,
                 source, fetched_at, created_at, updated_at)
            VALUES
            <foreach collection="rows" item="item" separator=",">
                (#{item.snapshotTradeDate}, #{item.stockCode}, #{item.stockName}, #{item.market},
                 #{item.etf}, #{item.industry},
                 #{item.price}, #{item.pctChange}, #{item.amount}, #{item.turnoverRate},
                 #{item.totalMarketCap}, #{item.floatMarketCap},
                 #{item.pb}, #{item.peTtm}, #{item.roe}, #{item.revenueGrowth}, #{item.profitGrowth},
                 #{item.grossMargin}, #{item.debtRatio},
                 #{item.dividendYield}, #{item.bps}, #{item.listDate}, #{item.change60d},
                 #{item.ytdChange}, #{item.source}, #{item.fetchedAt}, #{item.createdAt},
                 #{item.updatedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                stock_name = VALUES(stock_name),
                market = VALUES(market),
                is_etf = VALUES(is_etf),
                industry = VALUES(industry),
                price = VALUES(price),
                pct_change = VALUES(pct_change),
                amount = VALUES(amount),
                turnover_rate = VALUES(turnover_rate),
                total_market_cap = VALUES(total_market_cap),
                float_market_cap = VALUES(float_market_cap),
                pb = VALUES(pb),
                pe_ttm = VALUES(pe_ttm),
                roe = VALUES(roe),
                revenue_growth = VALUES(revenue_growth),
                profit_growth = VALUES(profit_growth),
                gross_margin = VALUES(gross_margin),
                debt_ratio = VALUES(debt_ratio),
                dividend_yield = VALUES(dividend_yield),
                bps = VALUES(bps),
                list_date = VALUES(list_date),
                change_60d = VALUES(change_60d),
                ytd_change = VALUES(ytd_change),
                source = VALUES(source),
                fetched_at = VALUES(fetched_at),
                updated_at = VALUES(updated_at)
            </script>
            """)
    int upsertBatch(@Param("rows") List<ScreenerUniverseStock> rows);

    /**
     * 一次读回某个交易日的全部明细。走 {@code uk_screener_universe_stock} 的前缀。
     *
     * <p><b>{@code is_etf AS etf} 这个别名不是可有可无的，别改回 {@code SELECT *}。</b>
     * 实体字段叫 {@code etf}，而这一列在库里叫 {@code is_etf}。MyBatis 的自动映射按
     * **列名推属性名**（下划线转驼峰），于是它去找 {@code isEtf} 这个属性——找不到。
     * 找不到的结果不是报错，是**这一列被静默丢掉**，读回来永远是 false。
     *
     * <p>今天还没有任何规则读这个字段（{@code StockRow.isEtf} 目前只被本类的映射搬到实体上），
     * 所以现在丢掉它只是让往返不再忠实；但它正是那种「将来某条规则要求区分 ETF 时才被发现、
     * 而那时已经存了几百天错数据」的东西——一个恒为 false 的布尔字段，看上去和真实值一模一样。
     * 所以要么给别名、要么在 {@code @Results} 里写死，两条路都行，唯独不能指望
     * {@code @TableField} —— 那是 MP 自己拼 SQL 时用的，管不到这条手写的 {@code @Select}。
     *
     * <p>其余列的列名与属性名一一对得上，所以没有第二处需要别名。列出的这一串
     * 与 INSERT 里的那 28 列一一对应：加列时两处一起改，别只改一处。
     */
    @Select("""
            SELECT id, snapshot_trade_date, stock_code, stock_name, market, is_etf AS etf, industry,
                   price, pct_change, amount, turnover_rate, total_market_cap, float_market_cap,
                   pb, pe_ttm, roe, revenue_growth, profit_growth, gross_margin, debt_ratio,
                   dividend_yield, bps, list_date, change_60d, ytd_change, source, fetched_at,
                   created_at, updated_at
            FROM screener_universe_stock
            WHERE snapshot_trade_date = #{tradeDate}
            ORDER BY stock_code
            """)
    List<ScreenerUniverseStock> findByTradeDate(@Param("tradeDate") LocalDate tradeDate);
}