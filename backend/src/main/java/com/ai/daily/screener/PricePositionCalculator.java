package com.ai.daily.screener;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 价格位置的纯计算。输入必须是**前复权**日线（kline {@code fqt=1}）——
 * 用原始价会在除权日显示假跌，把正常分红股判成「便宜」。
 *
 * <p>无网络、无 Spring 依赖，可直接单测，见 {@code PricePositionCalculatorTest}。
 */
public final class PricePositionCalculator {

    /** 一年按 250 个交易日算。 */
    public static final int WINDOW = 250;
    /** 年化波动至少要这么多根日线才算得出来。 */
    public static final int MIN_BARS_FOR_VOLATILITY = 60;
    /** 日线最后一根超过这个天数就算陈旧。 */
    public static final int STALE_DAYS = 15;

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private PricePositionCalculator() {}

    /** 一根日线。 */
    public record Bar(LocalDate date, BigDecimal close) {}

    public static PricePosition compute(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return PricePosition.unavailable("未取到日线，价格位置未确认");
        }
        // 非正收盘价是坏数据，不是「便宜」。留着会直接把价格分位压到 0 并伪造一个巨大回撤。
        List<Bar> sorted = bars.stream()
                .filter(b -> b != null && b.date() != null && b.close() != null && b.close().signum() > 0)
                .sorted(Comparator.comparing(Bar::date))
                .toList();
        if (sorted.isEmpty()) {
            return PricePosition.unavailable("日线无有效收盘价，价格位置未确认");
        }

        PricePosition p = new PricePosition();
        p.setAvailable(true);
        p.setBarCount(sorted.size());
        p.setLastTradeDate(sorted.get(sorted.size() - 1).date());

        List<Bar> window = sorted.size() > WINDOW
                ? sorted.subList(sorted.size() - WINDOW, sorted.size())
                : sorted;

        BigDecimal last = window.get(window.size() - 1).close();
        BigDecimal high = window.stream().map(Bar::close).max(BigDecimal::compareTo).orElse(last);
        BigDecimal low = window.stream().map(Bar::close).min(BigDecimal::compareTo).orElse(last);

        if (high.signum() > 0) {
            p.setDrawdownFromHigh(percent(high.subtract(last), high));
        } else {
            p.getDegradations().add("近一年最高价非正，回撤未确认");
        }

        BigDecimal span = high.subtract(low);
        if (span.signum() > 0) {
            p.setPricePercentile(percent(last.subtract(low), span).setScale(2, RoundingMode.HALF_UP));
        } else {
            // 一年内价格没有区间，分位没有意义，给中位而不是编一个数。
            p.setPricePercentile(new BigDecimal("50.00"));
            p.getDegradations().add("近一年价格无波动区间，价格分位按 50 处理");
        }

        p.setMaxDrawdownInYear(maxDrawdown(window));
        p.setAnnualizedVolatility(annualizedVolatility(window, p));
        p.setVsMa20(vsMovingAverage(window, 20, last, "MA20", p));
        p.setVsMa60(vsMovingAverage(window, 60, last, "MA60", p));
        p.setVsMa250(vsMovingAverage(window, 250, last, "MA250", p));
        return p;
    }

    /** 日线是否陈旧（最后一根距今超过 15 个自然日）。停牌股会在这里露出来。 */
    public static boolean isStale(PricePosition p, LocalDate today) {
        if (p == null || !p.isAvailable() || p.getLastTradeDate() == null) return true;
        return java.time.temporal.ChronoUnit.DAYS.between(p.getLastTradeDate(), today) > STALE_DAYS;
    }

    /** 近一年峰谷最大回撤 %（正数）。 */
    static BigDecimal maxDrawdown(List<Bar> window) {
        BigDecimal peak = null;
        BigDecimal worst = BigDecimal.ZERO;
        for (Bar b : window) {
            BigDecimal c = b.close();
            if (peak == null || c.compareTo(peak) > 0) peak = c;
            if (peak.signum() > 0) {
                BigDecimal dd = percent(peak.subtract(c), peak);
                if (dd.compareTo(worst) > 0) worst = dd;
            }
        }
        return worst.setScale(2, RoundingMode.HALF_UP);
    }

    /** 年化波动 %：对数收益样本标准差 × √250。样本不足时标降级并返回 null。 */
    static BigDecimal annualizedVolatility(List<Bar> window, PricePosition p) {
        if (window.size() < MIN_BARS_FOR_VOLATILITY) {
            p.getDegradations().add("日线不足 " + MIN_BARS_FOR_VOLATILITY + " 根，年化波动未确认");
            return null;
        }
        double[] r = new double[window.size() - 1];
        int n = 0;
        for (int i = 1; i < window.size(); i++) {
            double prev = window.get(i - 1).close().doubleValue();
            double cur = window.get(i).close().doubleValue();
            if (prev <= 0 || cur <= 0) continue;
            r[n++] = Math.log(cur / prev);
        }
        if (n < MIN_BARS_FOR_VOLATILITY - 1) {
            p.getDegradations().add("有效日线收益不足，年化波动未确认");
            return null;
        }
        double mean = 0;
        for (int i = 0; i < n; i++) mean += r[i];
        mean /= n;
        double var = 0;
        for (int i = 0; i < n; i++) var += (r[i] - mean) * (r[i] - mean);
        var /= (n - 1);
        double vol = Math.sqrt(var) * Math.sqrt(250) * 100;
        return BigDecimal.valueOf(vol).setScale(2, RoundingMode.HALF_UP);
    }

    /** 收盘价对 N 日均线的偏离 %（正 = 均线上方）。样本不足时标降级并返回 null。 */
    static BigDecimal vsMovingAverage(List<Bar> window, int n, BigDecimal last, String label, PricePosition p) {
        if (window.size() < n) {
            p.getDegradations().add("日线不足 " + n + " 根，" + label + " 未确认");
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = window.size() - n; i < window.size(); i++) {
            sum = sum.add(window.get(i).close());
        }
        BigDecimal ma = sum.divide(BigDecimal.valueOf(n), MC);
        if (ma.signum() <= 0) {
            p.getDegradations().add(label + " 非正，未确认");
            return null;
        }
        return percent(last.subtract(ma), ma).setScale(2, RoundingMode.HALF_UP);
    }

    /** (numerator / denominator) × 100，保留 4 位。 */
    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return numerator.multiply(BigDecimal.valueOf(100), MC)
                .divide(denominator, MC)
                .setScale(4, RoundingMode.HALF_UP);
    }
}