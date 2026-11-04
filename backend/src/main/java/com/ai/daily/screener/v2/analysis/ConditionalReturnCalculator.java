package com.ai.daily.screener.v2.analysis;

import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 历史条件收益分布：**当价格分位和今天差不多时，后来一年 / 三年的收益长什么样**。
 *
 * <p>这是描述性统计，不是预测。它回答「现在这个位置在过去意味着什么」，
 * 但样本来自同一段历史、且相互重叠，不能当作未来收益的概率。
 *
 * <p>口径：
 * <ol>
 *   <li>逐日计算 250 根日线的价格分位（和 {@code TechnicalCalculator} 一致）。</li>
 *   <li>取分位落在「当前分位 ± {@value #BAND} 个百分点」内的历史日期作为样本。</li>
 *   <li>样本起点每 {@value #STRIDE} 个交易日取一个，降低重叠程度。</li>
 *   <li>从样本日起算未来 250 / 750 根日线的收盘价收益。</li>
 *   <li>样本少于 {@value #MIN_SAMPLES} 个时不给数字，只说明为什么没有。</li>
 * </ol>
 *
 * <p>纯函数：不查库、不走网络、不读系统时钟。
 */
public final class ConditionalReturnCalculator {

    /** 当前分位两侧各取多少个百分点。 */
    static final int BAND = 10;
    /** 样本起点间隔（交易日）。 */
    static final int STRIDE = 5;
    /** 少于这个样本数就不出统计量。 */
    static final int MIN_SAMPLES = 20;
    /** 计算历史分位至少需要的一年窗口。 */
    static final int PERCENTILE_WINDOW = 250;

    private ConditionalReturnCalculator() {
    }

    /** 一个持有期的历史统计。 */
    public record HorizonStats(
            String key,
            String label,
            int sampleCount,
            BigDecimal medianReturnPct,
            BigDecimal p25ReturnPct,
            BigDecimal p75ReturnPct,
            BigDecimal winRatePct
    ) {}

    /** 整块结果。{@code available=false} 时 {@code status} 说明原因。 */
    public record Outlook(
            boolean available,
            BigDecimal currentPercentile,
            List<HorizonStats> horizons,
            String status,
            String note
    ) {

        public static Outlook unavailable(String status) {
            return new Outlook(false, null, List.of(), status, NOTE);
        }
    }

    static final String NOTE =
            "历史统计来自本标的已有日线，样本相互重叠，且不包含未来可能出现的制度、行业与估值变化；"
                    + "它不预测收益，也不构成投资建议。";

    /** 持有期定义：1 年 ≈ 250 根、3 年 ≈ 750 根日线。 */
    private record Horizon(String key, String label, int bars) {}

    private static final List<Horizon> HORIZONS = List.of(
            new Horizon("1y", "未来 1 年（250 个交易日）", 250),
            new Horizon("3y", "未来 3 年（750 个交易日）", 750));

    /**
     * @param input            日线，任意顺序，内部会排序并过滤无效收盘价
     * @param currentPercentile 当前的一年价格分位 0–100；null 表示算不出来
     */
    public static Outlook compute(List<WarehouseRows.PriceBar> input, BigDecimal currentPercentile) {
        if (currentPercentile == null) {
            return Outlook.unavailable("日线不足 250 根，无法确定当前价格分位，历史条件收益未计算");
        }
        List<WarehouseRows.PriceBar> bars = usable(input);
        if (bars.size() < PERCENTILE_WINDOW + STRIDE) {
            return Outlook.unavailable("日线不足 " + (PERCENTILE_WINDOW + STRIDE)
                    + " 根，没有可用于对照的历史位置");
        }

        List<HorizonStats> stats = new ArrayList<>();
        for (Horizon horizon : HORIZONS) {
            if (bars.size() < PERCENTILE_WINDOW + horizon.bars()) continue;
            HorizonStats s = stats(bars, currentPercentile, horizon);
            if (s != null) stats.add(s);
        }
        if (stats.isEmpty()) {
            return new Outlook(false, scale(currentPercentile), List.of(),
                    "历史上与当前价格分位相近、且之后还有完整一年持有期的样本不足 "
                            + MIN_SAMPLES + " 个，暂不给历史收益分布",
                    NOTE);
        }
        return new Outlook(true, scale(currentPercentile), List.copyOf(stats), null, NOTE);
    }

    private static HorizonStats stats(List<WarehouseRows.PriceBar> bars,
                                      BigDecimal currentPercentile, Horizon horizon) {
        double target = currentPercentile.doubleValue();
        List<Double> returns = new ArrayList<>();
        for (int i = PERCENTILE_WINDOW - 1; i + horizon.bars() < bars.size(); i += STRIDE) {
            Double percentile = percentileAt(bars, i);
            if (percentile == null || Math.abs(percentile - target) > BAND) continue;
            double start = bars.get(i).closePrice().doubleValue();
            double end = bars.get(i + horizon.bars()).closePrice().doubleValue();
            if (start <= 0 || end <= 0) continue;
            returns.add((end / start - 1.0) * 100.0);
        }
        if (returns.size() < MIN_SAMPLES) return null;

        returns.sort(Comparator.naturalOrder());
        int n = returns.size();
        double median = returns.get(n / 2);
        double p25 = returns.get((int) Math.floor((n - 1) * 0.25));
        double p75 = returns.get((int) Math.floor((n - 1) * 0.75));
        long wins = returns.stream().filter(v -> v > 0).count();
        return new HorizonStats(horizon.key(), horizon.label(), n,
                pct(median), pct(p25), pct(p75),
                pct(wins * 100.0 / n));
    }

    /**
     * 第 i 根收盘价在过去 250 根（含当天）里的分位。
     *
     * <p>用**排名口径**（不高于当天的根数 / 窗口根数），与
     * {@code TechnicalCalculator.percentileInWindow} 完全一致；不能换成
     * 「(收盘-最低)/(最高-最低)」，两种口径在震荡市里会给出不同的数，
     * 那就变成拿一种分位去找另一种分位的样本了。
     */
    private static Double percentileAt(List<WarehouseRows.PriceBar> bars, int i) {
        int from = i - PERCENTILE_WINDOW + 1;
        if (from < 0) return null;
        double close = bars.get(i).closePrice().doubleValue();
        int notHigher = 0;
        for (int k = from; k <= i; k++) {
            if (bars.get(k).closePrice().doubleValue() <= close) notHigher++;
        }
        return notHigher * 100.0 / PERCENTILE_WINDOW;
    }

    private static List<WarehouseRows.PriceBar> usable(List<WarehouseRows.PriceBar> input) {
        if (input == null) return List.of();
        List<WarehouseRows.PriceBar> out = new ArrayList<>();
        for (WarehouseRows.PriceBar b : input) {
            if (b != null && b.tradeDate() != null && b.closePrice() != null
                    && b.closePrice().signum() > 0) {
                out.add(b);
            }
        }
        out.sort(Comparator.comparing(WarehouseRows.PriceBar::tradeDate));
        return out;
    }

    private static BigDecimal pct(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP);
    }
}
