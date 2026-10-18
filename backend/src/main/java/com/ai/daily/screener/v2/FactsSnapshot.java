package com.ai.daily.screener.v2;

import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 一只标的在当前时点的事实快照。
 *
 * <p>这是 P3 的产物，也是 P4 打分引擎**唯一的输入**：打分、区间、情景都只能从这里读，
 * 不再回头外呼或查库。这样同一份快照可以在单测里逐字段复现，页面上的分数也不会
 * 因为两次查询之间源抖了一下而变来变去。
 *
 * <p><b>每个域都带 {@link Domain} 包装</b>：数值、状态、口径说明、数据日四件事绑在一起。
 * 只传 List 再另传一个 status map，等于允许两者错位——而那正是「页面写着数据不足，
 * 实际有数」或者反过来那类 bug 的来源。
 *
 * <p>缺失一律是空值 / 空列表，**绝不补 0**。0 会被打分当成真实数值。
 */
public record FactsSnapshot(
        String inputCode,
        InstrumentCode instrument,
        Identity identity,
        Domain<Quote> quote,
        Domain<List<WarehouseRows.PriceBar>> prices,
        Domain<List<WarehouseRows.ValuationPoint>> valuations,
        /** 指数/ETF 的估值来自 {@code market_valuation_history}，与个股的估值序列是两条路。 */
        Domain<IndexValuation> indexValuation,
        Domain<List<WarehouseRows.FinancialReport>> financials,
        Domain<List<WarehouseRows.MoneyFlow>> moneyflow,
        Domain<WarehouseRows.IndexProfile> indexProfile,
        Domain<List<WarehouseRows.IndustryWeight>> industry,
        Domain<List<WarehouseRows.Event>> events,
        /** 本轮已经发现缺口、并已排了后台同步任务。 */
        boolean syncPending,
        /** 被限流时告诉前端等多久；没有限流就是 0。 */
        long retryAfterMs,
        /** 本轮没有从源拿到任何新数据（行情/日线/估值都命中了本地）。 */
        boolean fromCache,
        /** 价格 / 估值 / 财务三个 asOf 的哈希，贯穿 core/detail/brief。 */
        String snapshotId,
        String snapshotAt
) {

    /** 身份信息。{@code name} 可能为空——那说明连行情源都没认出这个代码。 */
    public record Identity(
            String rawCode,
            String assetType,
            String market,
            String name,
            String industry,
            String industryCode,
            String trackingIndexCode,
            LocalDate listDate,
            String status,
            String sectorProfile
    ) {
    }

    /**
     * 当前行情。
     *
     * <p>{@code asOf} 是**报价所属交易日**，不是请求时刻——两个时间点混用会让新鲜度判断
     * 在午休、收盘后、周末各错一次。
     */
    public record Quote(
            String provider,
            String providerLabel,
            BigDecimal price,
            BigDecimal pctChange,
            BigDecimal amount,
            BigDecimal turnoverRate,
            BigDecimal totalMarketCap,
            BigDecimal peTtm,
            BigDecimal pb,
            BigDecimal dividendYield,
            LocalDate asOf
    ) {
    }

    /**
     * 指数/ETF 的估值分位。口径名必须随数字一起走。
     *
     * <p>{@code peQ25}/{@code peQ40} 是**同一段历史、同一个口径**的分位数，
     * 只给参考区间用。它们必须和 {@code percentileMethod} 一起读：中证口径的 q25
     * 套到蛋卷口径的 PE 上，算出来的区间看起来完全正常，但没有任何依据。
     */
    public record IndexValuation(
            String source,
            String sourceLabel,
            String percentileMethod,
            BigDecimal peTtm,
            BigDecimal percentile,
            BigDecimal peQ25,
            BigDecimal peQ40,
            BigDecimal peMedian,
            /** 与 PE 分位同窗口滚动的 PB 分位；只有提供 PB 历史的源才有值。 */
            BigDecimal pbPercentile,
            LocalDate tradeDate,
            Integer historyLength,
            LocalDate historyFrom,
            LocalDate historyTo
    ) {
    }

    /**
     * 一个数据域：值 + 状态 + 给用户看的原因 + 数据日。
     *
     * @param data   缺失时是 null / 空列表，不补占位值
     * @param status 见 {@link DataStatus}
     * @param note   给用户看的说明；状态是 OK 时可以为空
     * @param asOf   这一域最新数据所属日期（ISO），未知时 null
     */
    public record Domain<T>(T data, DataStatus status, String note, String asOf) {

        public boolean usable() {
            return status != null && status.usable();
        }

        public static <T> Domain<T> ok(T data, String asOf) {
            return new Domain<>(data, DataStatus.OK, null, asOf);
        }

        public static <T> Domain<T> of(T data, DataStatus status, String note, String asOf) {
            return new Domain<>(data, status, note, asOf);
        }

        public static <T> Domain<T> failed(String note) {
            return new Domain<>(null, DataStatus.FAILED, note, null);
        }

        public static <T> Domain<T> empty(String note) {
            return new Domain<>(null, DataStatus.EMPTY, note, null);
        }

        public static <T> Domain<T> notApplicable(String note) {
            return new Domain<>(null, DataStatus.NOT_APPLICABLE, note, null);
        }
    }
}
