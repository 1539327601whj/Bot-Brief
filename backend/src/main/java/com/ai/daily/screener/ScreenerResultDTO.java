package com.ai.daily.screener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 「低估精选」一次扫描的结果。
 *
 * <p>顺序固定：**指数基金在前，个股在后**（用户明确要求先给破产/下市风险低的）。
 * 字段全部是可复核的数字与标注，没有任何定性预测。
 */
public record ScreenerResultDTO(
        /** 本次计算时间（Asia/Shanghai）。 */
        String dataTime,
        /** 日线最后一根的日期，用于告诉用户「这些价格位置截止到哪天」。 */
        String priceAsOf,
        /** 本次生效的条件（解析默认值之后），方便用户回看自己到底筛了什么。 */
        ScreenerParams appliedParams,
        Summary summary,
        List<IndexFundItem> indexFunds,
        List<ScreeningRules.Selected> steadyStocks,
        List<ScreeningRules.Selected> growthStocks,
        String disclaimer
) {

    /** 口径摘要。**各规则剔除数之和 + 进入打分数 = 扫描总数**，对不上就是 bug。 */
    public record Summary(
            int scanned,
            int afterVetoes,
            int steadyPool,
            int growthPool,
            int shortlistFetched,
            List<ScreeningRules.VetoCount> vetoCounts,
            List<String> degradations,
            List<String> notes
    ) {}

    /** 指数基金一条。估值分位缺失时 {@code percentileStatus} 是「估值分位未接入」，不是编的数。 */
    public record IndexFundItem(
            String code,
            String name,
            String tracking,
            BigDecimal price,
            BigDecimal pctChange,
            /** 当日成交额（亿元）。 */
            BigDecimal amountYi,
            /** 基金规模 / 总市值（亿元）。 */
            BigDecimal scaleYi,
            BigDecimal pricePercentile,
            BigDecimal drawdownFromHigh,
            BigDecimal maxDrawdownInYear,
            BigDecimal annualizedVolatility,
            BigDecimal vsMa250,
            Integer barCount,
            LocalDate lastTradeDate,
            BigDecimal peTtm,
            BigDecimal pePercentile,
            String percentileMethod,
            String percentileStatus,
            LocalDate valuationTradeDate,
            List<String> notes
    ) {}
}