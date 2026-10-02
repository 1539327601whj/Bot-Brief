package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 全市场快照的**明细**：每只标的每个交易日一行。字段与
 * {@link com.ai.daily.screener.StockRow} 一一对应。
 *
 * <p><b>除主键与身份列外全部可空。</b>章程 §6 要求「缺了就是缺了」：行情接口没给的字段
 * 必须落成 NULL，不能写成 0——写成 0 会让它看起来是一家「PE=0、负债率=0」的好公司，
 * 正好从排雷规则里穿过去。
 *
 * <p>{@code etf} 是**原样存下来的解析结果**，不在读侧按代码前缀重算。
 * 重算等于给同一件事写第二套判据，两边漂移时不会报错，只会让数据悄悄变样。
 */
@Data
@TableName("screener_universe_stock")
public class ScreenerUniverseStock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属快照的交易日；与头表的 {@code trade_date} 对应，不入外键，靠写入时同事务保证。 */
    private LocalDate snapshotTradeDate;

    private String stockCode;

    private String stockName;

    /** 东财市场标识：1 = 沪市，0 = 深市。拼 secid 用。 */
    private Integer market;

    @TableField("is_etf")
    private boolean etf;

    private String industry;

    private BigDecimal price;

    private BigDecimal pctChange;

    private BigDecimal amount;

    private BigDecimal turnoverRate;

    private BigDecimal totalMarketCap;

    private BigDecimal floatMarketCap;

    private BigDecimal pb;

    private BigDecimal peTtm;

    private BigDecimal roe;

    private BigDecimal revenueGrowth;

    private BigDecimal profitGrowth;

    private BigDecimal grossMargin;

    private BigDecimal debtRatio;

    private BigDecimal dividendYield;

    private BigDecimal bps;

    private LocalDate listDate;

    private BigDecimal change60d;

    private BigDecimal ytdChange;

    private String source;

    private LocalDateTime fetchedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}