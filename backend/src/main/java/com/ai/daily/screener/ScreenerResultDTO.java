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

    /**
     * 指数基金一条。**单位是指数**，ETF 只是取价格位置与规模的手段。
     *
     * <h4>缺值不留空：每个可空数值配一个状态串</h4>
     *
     * <p>硬规则：<b>{@code xStatus != null ⟺ 对应的数值 == null}</b>。有值时必须为 null，
     * 不能两样都给——两样都给会立刻退化成「显示哪个」的模糊地带，那正是要消掉的东西。
     * 前端 {@code cell(value, status, format)} 就是照这条写的：有值渲染值，无值渲染 status。
     *
     * <p>唯一的例外是 {@code positionStatus} 一对五（价格分位 / 距最高点 / 最大回撤 / 年化波动 /
     * 对 MA250）：这五个数出自同一次日线计算，要么全有要么全无，拆成五个状态串只会把同一句原因
     * 印五遍。{@code barCount} 与 {@code lastTradeDate} 也随之同为 null。
     *
     * <p><b>缺值原因不能兜成 0。</b>状态串要说清「为什么缺 + 下一步怎样」，
     * 见章程 §6「缺了就是缺了，要标出来」。
     */
    public record IndexFundItem(
            // ---- 身份：指数在前，ETF 在后 ----
            String indexCode,
            String indexName,
            /** 类别枚举，见 index-pool.json：broad / strategy / sector / theme / overseas / other。 */
            String category,
            /** 没有可交易的代表 ETF 时为 null，此时下面所有依赖行情的格子都带状态串。 */
            String etfCode,
            String etfName,
            /** 该指数跟踪的名字，用于「这个 ETF 跟的是哪个指数」。 */
            String tracking,

            // ---- 数值 ----
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
            BigDecimal peTtm,
            BigDecimal pePercentile,

            // ---- 缺值原因：非 null ⟺ 上面同组的数值为 null ----
            String priceStatus,
            String pctChangeStatus,
            String amountStatus,
            String scaleStatus,
            String positionStatus,
            String peStatus,
            String percentileStatus,

            // ---- 来源：有值时说清「这个数是哪来的」，无值时随数值一起为 null ----
            String quoteSource,
            String positionSource,
            /** 估值来源的中文写法（中证指数官网 / 蛋卷基金）。识别不出的原样给出。 */
            String valuationSource,
            String percentileMethod,
            LocalDate valuationTradeDate,

            Integer barCount,
            /** 日线最后一根。卡片上要印出来——「这条曲线有多旧」是读者该自己判断的事实。 */
            LocalDate lastTradeDate,

            /**
             * 口径说明，不是缺值原因（缺值原因一律在状态串里）。
             *
             * <p>例如「PE 分位是【指数】的口径，不是该 ETF 自身的分位；
             * 不同估值来源的口径不同，不可横向比较」——这句话在每张卡上都要有，
             * 因为它防的是本页最容易被误读的那件事。
             */
            List<String> notes
    ) {}
}