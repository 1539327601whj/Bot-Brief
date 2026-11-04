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
    /** 3 年 / 5 年窗口（交易日）。 */
    public static final int THREE_YEAR_WINDOW = 750;
    public static final int FIVE_YEAR_WINDOW = 1250;
    /**
     * 长周期指标至少要有窗口的 80% 才计算。少太多时「3 年 CAGR」实际是
     * 「1 年半的收益年化」，数字会好看得多，不能拿它当长期结论。
     */
    public static final double MIN_LONG_TERM_COVERAGE = 0.8;
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
        fillLongTerm(sorted, p);
        return p;
    }

    /**
     * 3 / 5 年长期指标。**样本不足就留空，不拿短样本年化冒充长期收益。**
     *
     * <p>这些数只用于「长期是否向上」的风险提示，不参与价格位置打分——
     * 打分仍是「同批候选里的相对位置」，长期收益不改变这一档的定义。
     */
    private static void fillLongTerm(List<Bar> sorted, PricePosition p) {
        List<Bar> threeYears = tail(sorted, THREE_YEAR_WINDOW);
        p.setThreeYearCagr(cagr(threeYears, THREE_YEAR_WINDOW));
        p.setFiveYearCagr(cagr(tail(sorted, FIVE_YEAR_WINDOW), FIVE_YEAR_WINDOW));

        if (threeYears.size() >= minBars(THREE_YEAR_WINDOW)) {
            Drawdown drawdown = drawdown(threeYears);
            p.setMaxDrawdownThreeYear(drawdown.percent());
            p.setThreeYearDrawdownRecovered(drawdown.recovered());
        }
    }

    private static List<Bar> tail(List<Bar> bars, int window) {
        return bars.size() > window ? bars.subList(bars.size() - window, bars.size()) : bars;
    }

    private static int minBars(int window) {
        return (int) Math.ceil(window * MIN_LONG_TERM_COVERAGE);
    }

    /** 首尾收盘价的年化收益 %；覆盖不足时返回 null。 */
    static BigDecimal cagr(List<Bar> window, int targetBars) {
        if (window == null || window.size() < minBars(targetBars)) return null;
        Bar first = window.get(0);
        Bar last = window.get(window.size() - 1);
        long days = java.time.temporal.ChronoUnit.DAYS.between(first.date(), last.date());
        double years = days / 365.25;
        // 覆盖率够但日历跨度不够（例如中间停牌很久）时，年化仍然不是「3 年」的数
        double minYears = targetBars >= FIVE_YEAR_WINDOW ? 4.0 : 2.5;
        if (years < minYears || first.close().signum() <= 0) return null;
        double ratio = last.close().doubleValue() / first.close().doubleValue();
        if (ratio <= 0) return null;
        double value = (Math.pow(ratio, 1.0 / years) - 1.0) * 100.0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    record Drawdown(BigDecimal percent, boolean recovered) {}

    /** 峰谷最大回撤，以及之后是否回到回撤前的高点。 */
    static Drawdown drawdown(List<Bar> window) {
        BigDecimal peak = null;
        BigDecimal worstPeak = null;
        BigDecimal worst = BigDecimal.ZERO;
        boolean recovered = true;
        for (Bar b : window) {
            BigDecimal close = b.close();
            if (peak == null || close.compareTo(peak) > 0) peak = close;
            if (peak.signum() > 0) {
                BigDecimal dd = percent(peak.subtract(close), peak);
                if (dd.compareTo(worst) > 0) {
                    worst = dd;
                    worstPeak = peak;
                    recovered = false;
                }
            }
            if (worstPeak != null && close.compareTo(worstPeak) >= 0) recovered = true;
        }
        return new Drawdown(worst.setScale(2, RoundingMode.HALF_UP), recovered);
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