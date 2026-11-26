package com.ai.daily.screener.v2;

import com.ai.daily.screener.EtfResearch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 代码查询 v2 的响应体。
 *
 * <p>字段按页面区块分组，但**状态与数值绑在一起**：每个数据域都带
 * {@code status / note / asOf}，不把状态放到另一个平行的 map 里让前端自己对齐。
 * 两者错位时页面会出现「写着数据不足，实际有数」这种最难查的 bug。
 *
 * <p>P3 先交付事实与数据状态；评分、结论、参考区间、情景在 P4 往
 * {@link CoreView} 里加字段（{@code verdict}/{@code dimensions}/{@code buyZone}），
 * 不改变现有字段的含义。
 */
public final class CodeLookupV2DTO {

    public static final String DISCLAIMER =
            "本页仅为公开数据的规则化整理结果，不构成投资建议、证券推荐或买卖依据。";

    private CodeLookupV2DTO() {
    }

    /** 首屏：身份、行情、估值、技术摘要、各域状态。 */
    public record CoreView(
            String inputCode,
            String code,
            String name,
            String assetType,
            String market,
            String industry,
            String sectorProfile,
            String trackingIndexCode,
            String snapshotId,
            String snapshotAt,
            boolean fromCache,
            boolean syncPending,
            long retryAfterMs,
            QuoteView quote,
            ValuationView valuation,
            TechnicalView technical,
            /** P4：规则结论。数据不足时 {@code verdict.withheldReason} 非空，分数为 null。 */
            VerdictView verdict,
            List<DimensionView> dimensions,
            BuyZoneView buyZone,
            /** 决策层：位置 / 时机 / 行动 / 长期短期。规则算出，AI 只能复述。 */
            DecisionView decision,
            /** 旧页 8 档价格回放：「五年前那一天 vs 今天」，不是区间涨幅。 */
            List<LookbackView> priceLookbacks,
            /** 个股 PE 回放；指数/ETF 的估值历史走「估值」标签页，这里为空。 */
            List<LookbackView> peLookbacks,
            Map<String, DomainView> data,
            List<String> notes,
            String rulesVersion,
            String disclaimer,
            EtfResearch.View etfResearch
    ) {
    }

    /**
     * 一档回放。{@code baseline/value} 为 null 时看 {@code status}：
     * 标签仍然存在，只是本地数据覆盖不到那一天，不能编一个数出来。
     */
    public record LookbackView(
            String label,
            String baselineDate,
            BigDecimal baseline,
            BigDecimal change,
            String status
    ) {
    }

    /** 下钻：财务、资金流、指数画像、行业权重、事件。每段独立状态。 */
    public record DetailView(
            String code,
            String snapshotId,
            boolean snapshotChanged,
            Section<List<FinancialView>> financials,
            Section<List<MoneyFlowView>> moneyflow,
            Section<IndexProfileView> indexProfile,
            /** ETF 专有：基金净资产、费率、跟踪误差。个股/指数为 {@code data=null}。 */
            Section<FundProfileView> fundProfile,
            Section<List<IndustryView>> industry,
            Section<List<EventView>> events,
            /** P4：三张条件推演卡；结论 withheld 时为空列表。 */
            List<ScenarioView> scenarios,
            List<String> notes
    ) {
    }

    /** 图表序列。{@code interval} 可能是后端降采样后的实际粒度（例如请求 1d 但给了 1w）。 */
    public record SeriesView(
            String code,
            String kind,
            String range,
            String interval,
            List<PricePoint> price,
            List<ValuationPoint> valuation,
            List<MoneyFlowPoint> moneyflow,
            String note
    ) {
    }

    /** 同步状态。前端据此决定「财务还在同步，请稍候」还是「源失败了，重试」。 */
    public record StatusView(
            String code,
            String snapshotId,
            String priceAsOf,
            String valuationAsOf,
            String financialAsOf,
            String eventsAsOf,
            boolean syncPending,
            long retryAfterMs,
            Map<String, DomainView> data,
            List<JobView> jobs,
            String checkedAt
    ) {
    }

    public record SearchHit(
            String instrumentCode,
            String code,
            String name,
            String assetType,
            String market
    ) {
    }

    // ------------------------------------------------------------------
    // 数值视图
    // ------------------------------------------------------------------

    public record QuoteView(
            String provider,
            String providerLabel,
            BigDecimal price,
            BigDecimal pctChange,
            BigDecimal amount,
            BigDecimal turnoverRate,
            BigDecimal marketCap,
            BigDecimal peTtm,
            BigDecimal pb,
            BigDecimal dividendYield,
            String asOf
    ) {
    }

    /**
     * 估值视图。{@code pePercentile} 必须是**同一条 PE 序列**算出来的；
     * 跨源拼出来的分位在 P4 会被直接作废，这里也不给。
     */
    public record ValuationView(
            String source,
            String sourceLabel,
            String method,
            BigDecimal peTtm,
            BigDecimal pePercentile,
            String peWindowFrom,
            int pePoints,
            BigDecimal pbMrq,
            BigDecimal pbPercentile,
            BigDecimal dividendYield,
            Integer historyLength,
            String asOf,
            String note
    ) {
    }

    public record TechnicalView(
            int barCount,
            String lastDate,
            BigDecimal price,
            boolean hasOhlc,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma120,
            BigDecimal ma250,
            BigDecimal atr14,
            BigDecimal low20,
            BigDecimal low60,
            BigDecimal low120,
            BigDecimal high1y,
            BigDecimal low1y,
            BigDecimal pricePercentile1y,
            BigDecimal momentum20d,
            BigDecimal momentum60d
    ) {
    }

    public record DomainView(String status, String note, String asOf) {
    }

    // ------------------------------------------------------------------
    // P4 分析视图
    // ------------------------------------------------------------------

    /**
     * 结论。{@code score == null} 表示 withheld：页面只能显示
     * {@code withheldReason}，**不能**用 0 分顶替。
     */
    public record VerdictView(
            String label,
            String labelKey,
            BigDecimal score,
            BigDecimal scoreLow,
            BigDecimal scoreHigh,
            BigDecimal coverage,
            BigDecimal confidence,
            BigDecimal eventAdjustment,
            List<String> hardFlags,
            List<ReasonView> reasons,
            List<ReasonView> risks,
            String withheldReason
    ) {
    }

    public record ReasonView(String text, List<String> evidenceIds) {
    }

    public record DimensionView(
            String key,
            String label,
            int weight,
            BigDecimal score,
            BigDecimal coverage,
            String status,
            List<MetricView> metrics,
            List<String> reasons
    ) {
    }

    public record MetricView(
            String key,
            String label,
            BigDecimal value,
            String display,
            BigDecimal score,
            double weight,
            String basis,
            String evidenceId
    ) {
    }

    public record BuyZoneView(
            String status,
            BigDecimal low,
            BigDecimal high,
            BigDecimal stopLoss,
            BigDecimal distanceToZonePct,
            BigDecimal riskPct,
            List<ReasonView> basis,
            List<String> notes
    ) {
    }

    /**
     * 决策层。标签是封闭集合：
     * <ul>
     *   <li>位置：{@code LOW / NEUTRAL / HIGH / INSUFFICIENT}</li>
     *   <li>时机：{@code ACCUMULATE / HOLD / WAIT_STABILIZE / WAIT_PULLBACK / AVOID / INSUFFICIENT}</li>
     *   <li>行动：{@code BUILD_GRADUALLY / HOLD_OBSERVE / WAIT / AVOID / INSUFFICIENT}</li>
     *   <li>周期：{@code LONG / SHORT / BOTH / INSUFFICIENT}</li>
     * </ul>
     * 数据不足时一律 {@code INSUFFICIENT}，不用「中性」或 0 分顶替。
     */
    public record DecisionView(
            DecisionPositionView position,
            DecisionTimingView timing,
            DecisionActionView action,
            DecisionHorizonView horizon,
            /** 本标的自己的长期趋势：3/5 年年化与 3 年回撤修复。 */
            LongTermView longTerm,
            /** 历史条件收益分布：当前价格分位附近，历史之后 1 / 3 年的收益统计。 */
            HistoricalOutlookView outlook,
            List<ReasonView> reasons,
            List<ReasonView> risks,
            List<String> watchItems,
            String summary,
            String disclaimer
    ) {
    }

    /**
     * 长期趋势。收益与回撤都是百分数（8.2 = 8.2%）。
     *
     * <p>{@code key}：{@code UP} / {@code FLAT_OR_DOWN} / {@code MIXED} / {@code INSUFFICIENT}。
     * 样本不足时数值为 null，由 {@code summary} 说明缺什么。
     */
    public record LongTermView(
            String key,
            String label,
            BigDecimal threeYearCagrPct,
            BigDecimal fiveYearCagrPct,
            BigDecimal threeYearMaxDrawdownPct,
            Boolean drawdownRecovered,
            String summary
    ) {
    }

    /**
     * 历史条件收益。**描述性统计，不是预测**。
     *
     * <p>{@code available=false} 时不给任何数字，{@code status} 说明为什么；
     * 样本数、中位数、四分位与上涨占比都由后端算好，前端不要自己补 0。
     */
    public record HistoricalOutlookView(
            boolean available,
            BigDecimal currentPercentile,
            List<HorizonStatsView> horizons,
            String status,
            String note
    ) {
    }

    /** 一个持有期的统计。收益单位是百分比。 */
    public record HorizonStatsView(
            String key,
            String label,
            int sampleCount,
            BigDecimal medianReturnPct,
            BigDecimal p25ReturnPct,
            BigDecimal p75ReturnPct,
            BigDecimal winRatePct
    ) {
    }

    /** 一个位置轴：+1 偏低位、0 中性、-1 偏高位。 */
    public record DecisionAxisView(
            String key,
            String label,
            String value,
            String display,
            int score,
            String status,
            String basis
    ) {
    }

    public record DecisionPositionView(
            String key,
            String label,
            int availableAxes,
            int score,
            List<DecisionAxisView> axes,
            List<ReasonView> reasons
    ) {
    }

    public record DecisionTimingView(
            String key,
            String label,
            List<ReasonView> reasons,
            List<String> triggers
    ) {
    }

    public record DecisionActionView(
            String key,
            String label,
            List<String> tranchePlan,
            List<String> invalidation
    ) {
    }

    public record DecisionHorizonView(
            String key,
            String label,
            BigDecimal longScore,
            BigDecimal shortScore,
            BigDecimal gap,
            String summary
    ) {
    }

    public record ScenarioView(
            String key,
            String label,
            boolean triggered,
            String condition,
            String narrative,
            List<String> evidenceIds,
            String invalidation,
            String disclaimer
    ) {
    }

    /**
     * 大模型解读。{@code available=false} 有两种含义，由 {@code fallback} 区分：
     * 回退规则文案时 {@code fallback=true} 且 {@code sections} 有内容；
     * 调用失败时 {@code fallback=false} 且 {@code sections} 为空，{@code reason} 是真实原因。
     */
    public record BriefView(
            String code,
            String name,
            String snapshotId,
            boolean snapshotChanged,
            boolean available,
            boolean fallback,
            boolean cached,
            String title,
            List<BriefSectionView> sections,
            String reason,
            String note,
            String rulesVersion,
            String promptVersion,
            String generatedAt,
            String disclaimer
    ) {
    }

    public record BriefSectionView(String heading, String text, List<String> evidenceIds) {
    }

    // ------------------------------------------------------------------
    // 明细视图
    // ------------------------------------------------------------------

    public record Section<T>(T data, String status, String note, String asOf) {
    }

    public record FinancialView(
            String reportDate,
            String reportType,
            String announceDate,
            BigDecimal revenue,
            BigDecimal revenueYoy,
            BigDecimal netProfit,
            BigDecimal netProfitYoy,
            BigDecimal roe,
            BigDecimal grossMargin,
            BigDecimal netMargin,
            BigDecimal ocf,
            BigDecimal totalAssets,
            BigDecimal totalEquity,
            BigDecimal totalLiabilities,
            BigDecimal currentAssets,
            BigDecimal currentLiabilities,
            BigDecimal interestExpense,
            BigDecimal ebit,
            BigDecimal goodwill,
            BigDecimal accountsReceivable,
            String source
    ) {
    }

    public record MoneyFlowView(
            String tradeDate,
            BigDecimal mainNet,
            BigDecimal superNet,
            BigDecimal largeNet,
            BigDecimal mediumNet,
            BigDecimal smallNet,
            BigDecimal mainRatio,
            String source
    ) {
    }

    public record IndexProfileView(
            String indexCode,
            String name,
            String asOf,
            Integer constituentCount,
            BigDecimal top10Weight,
            BigDecimal peTtm,
            BigDecimal dividendYield,
            BigDecimal totalMarketCap,
            String source
    ) {
    }

    /**
     * 基金档案。{@code aum} 单位是**元**（与 {@link QuoteView#marketCap()} 一致），
     * 前端再换算成亿元显示；{@code aumAsOf} 是季报披露截止日，不是请求日。
     */
    public record FundProfileView(
            String code,
            String name,
            String fullName,
            String fundType,
            String company,
            String custodian,
            String manager,
            String inceptionDate,
            String inceptionScale,
            BigDecimal aum,
            String aumAsOf,
            BigDecimal managementFeePct,
            BigDecimal custodyFeePct,
            String benchmark,
            String trackingTarget,
            BigDecimal trackingErrorPct,
            BigDecimal peerTrackingErrorPct,
            String trackingErrorAsOf,
            String source
    ) {
    }

    public record IndustryView(
            String industryCode,
            String industryName,
            BigDecimal weight,
            String asOf
    ) {
    }

    public record EventView(
            String category,
            String eventType,
            String title,
            String summary,
            String url,
            String publishedAt,
            String source,
            String importance,
            String polarity,
            String instrumentCode
    ) {
    }

    public record JobView(
            String task,
            String status,
            String error,
            String startedAt,
            String finishedAt,
            String retryAfter
    ) {
    }

    // ------------------------------------------------------------------
    // 序列点
    // ------------------------------------------------------------------

    public record PricePoint(
            String date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal pctChange,
            BigDecimal turnoverRate
    ) {
    }

    public record ValuationPoint(
            String date,
            BigDecimal peTtm,
            BigDecimal pbMrq,
            BigDecimal dividendYield,
            BigDecimal percentile
    ) {
    }

    public record MoneyFlowPoint(
            String date,
            BigDecimal mainNet,
            BigDecimal superNet,
            BigDecimal largeNet,
            BigDecimal mediumNet,
            BigDecimal smallNet
    ) {
    }
}
