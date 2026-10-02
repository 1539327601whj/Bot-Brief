package com.ai.daily.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 全市场快照的**头**：每个交易日一行，承载 {@code UniverseSnapshot} 里那几个
 * 「一次扫描一个」的标量（取到几条、剔除几条、市值下限）。
 *
 * <p>这几个标量不能下放进明细表：那样每只标的一行会把它们重复几百遍，
 * 重跑时只要有一次写入不一致就产生静默歧义，而口径摘要必须自洽。
 *
 * <p>这张表**同时就是预取的幂等闸门**：{@code trade_date} 上有唯一键，
 * 「今天有没有这一行」等价于「今天的预取成功没有」。
 */
@Data
@TableName("screener_universe_snapshot")
public class ScreenerUniverseSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 清单所属交易日（东财 {@code latestTradeDate}），不是「今天」——节假日与周末不重合。 */
    private LocalDate tradeDate;

    /** 预取任务执行的自然日。与 {@code tradeDate} 分开存：跨零点重跑时两者会不一致。 */
    private LocalDate prefetchDate;

    /** 本次清单请求用的池子大小 N。存下来是为了事后能看出「口径为什么变了」。 */
    private Integer poolSize;

    private Integer listedCount;

    private Integer missingCount;

    private BigDecimal capFloor;

    private String source;

    private LocalDateTime fetchedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}