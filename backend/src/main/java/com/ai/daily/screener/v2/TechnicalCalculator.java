package com.ai.daily.screener.v2;

import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 从日线算技术摘要：均线、ATR、区间低点、一年分位、动量。
 *
 * <p>全部是纯函数，**不查库、不取当前时间**。打分（P4）对同一份日线必须给出同一个数，
 * 否则「上午看 62 分、下午看 58 分」会被误读成行情变了。
 *
 * <p>缺 OHLC 时按收盘价退化：腾讯兜底日线只有收盘价，硬要用 0 或前值补出高低价，
 * 会让 ATR 和支撑位看起来有精度、实际上全是假的。{@code null} 表示这一项算不出来。
 */
public final class TechnicalCalculator {

    private static final int ONE_YEAR_BARS = 250;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100L);

    private TechnicalCalculator() {
    }

    /**
     * 技术摘要。
     *
     * @param barCount   有效的日线根数
     * @param lastDate   最后一根日线的交易日
     * @param price      最后一根收盘价
     * @param hasOhlc    这段日线是否带高低价（决定 ATR 与支撑位是否可用）
     */
    public record TechnicalSummary(
            int barCount,
            LocalDate lastDate,
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
        public boolean enoughForTrend() {
            return barCount >= 60 && price != null;
        }
    }

    public static TechnicalSummary compute(List<WarehouseRows.PriceBar> input) {
        List<WarehouseRows.PriceBar> bars = usable(input);
        if (bars.isEmpty()) {
            return new TechnicalSummary(0, null, null, false,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        WarehouseRows.PriceBar last = bars.get(bars.size() - 1);
        BigDecimal price = last.closePrice();
        boolean hasOhlc = bars.stream().anyMatch(b -> b.highPrice() != null && b.lowPrice() != null);

        return new TechnicalSummary(
                bars.size(),
                last.tradeDate(),
                price,
                hasOhlc,
                averageClose(bars, 20),
                averageClose(bars, 60),
                averageClose(bars, 120),
                averageClose(bars, 250),
                atr(bars, 14),
                lowest(bars, 20),
                lowest(bars, 60),
                lowest(bars, 120),
                highest(bars, ONE_YEAR_BARS),
                lowestInWindow(bars, ONE_YEAR_BARS),
                percentileInWindow(bars, ONE_YEAR_BARS, price),
                momentum(bars, 20),
                momentum(bars, 60));
    }

    /** 去掉没有收盘价/日期的行，并按日期升序。输入通常已经是升序，这里只是兜底。 */
    private static List<WarehouseRows.PriceBar> usable(List<WarehouseRows.PriceBar> input) {
        List<WarehouseRows.PriceBar> out = new ArrayList<>();
        if (input == null) return out;
        for (WarehouseRows.PriceBar bar : input) {
            if (bar == null || bar.tradeDate() == null || bar.closePrice() == null
                    || bar.closePrice().signum() <= 0) {
                continue;
            }
            out.add(bar);
        }
        out.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));
        return out;
    }

    private static List<WarehouseRows.PriceBar> tail(List<WarehouseRows.PriceBar> bars, int n) {
        int from = Math.max(0, bars.size() - n);
        return bars.subList(from, bars.size());
    }

    private static BigDecimal averageClose(List<WarehouseRows.PriceBar> bars, int n) {
        if (bars.size() < n) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (WarehouseRows.PriceBar bar : tail(bars, n)) {
            sum = sum.add(bar.closePrice());
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    /** 支撑用的低点：有最低价就用最低价，没有就退回收盘价。 */
    private static BigDecimal lowest(List<WarehouseRows.PriceBar> bars, int n) {
        return lowestInWindow(bars, n);
    }

    private static BigDecimal lowestInWindow(List<WarehouseRows.PriceBar> bars, int n) {
        if (bars.isEmpty()) return null;
        BigDecimal low = null;
        for (WarehouseRows.PriceBar bar : tail(bars, n)) {
            BigDecimal value = bar.lowPrice() != null ? bar.lowPrice() : bar.closePrice();
            if (value != null && (low == null || value.compareTo(low) < 0)) {
                low = value;
            }
        }
        return low;
    }

    private static BigDecimal highest(List<WarehouseRows.PriceBar> bars, int n) {
        if (bars.isEmpty()) return null;
        BigDecimal high = null;
        for (WarehouseRows.PriceBar bar : tail(bars, n)) {
            BigDecimal value = bar.highPrice() != null ? bar.highPrice() : bar.closePrice();
            if (value != null && (high == null || value.compareTo(high) > 0)) {
                high = value;
            }
        }
        return high;
    }

    /**
     * 当前价在窗口内收盘价里的分位（0–100，越大越接近区间高位）。
     *
     * <p>用 {@code <= 当前价} 的根数占比，而不是「最低到最高的线性位置」：
     * 后者会被一根异常高低点拉偏，前者对大多数股票更稳健。
     */
    static BigDecimal percentileInWindow(List<WarehouseRows.PriceBar> bars, int n, BigDecimal price) {
        if (price == null) return null;
        List<WarehouseRows.PriceBar> window = tail(bars, n);
        if (window.isEmpty()) return null;
        int notHigher = 0;
        for (WarehouseRows.PriceBar bar : window) {
            if (bar.closePrice().compareTo(price) <= 0) {
                notHigher++;
            }
        }
        return BigDecimal.valueOf(notHigher)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(window.size()), 2, RoundingMode.HALF_UP);
    }

    /** 收盘价相对 N 根之前的涨跌幅（%）。根数不够返回 null，不拿更短的窗口顶替。 */
    static BigDecimal momentum(List<WarehouseRows.PriceBar> bars, int lookback) {
        if (bars.size() <= lookback) return null;
        BigDecimal past = bars.get(bars.size() - 1 - lookback).closePrice();
        BigDecimal now = bars.get(bars.size() - 1).closePrice();
        if (past == null || past.signum() == 0) return null;
        return now.subtract(past)
                .multiply(HUNDRED)
                .divide(past, 2, RoundingMode.HALF_UP);
    }

    /**
     * 14 日 ATR，用简单平均 TR（不做 Wilder 平滑）。
     *
     * <p>这里要的是「一天的正常波动幅度」，用来把支撑位换成区间，不是做交易系统；
     * 简单平均更容易在单测里手工核对，差别在 14 日窗口上可以忽略。
     */
    static BigDecimal atr(List<WarehouseRows.PriceBar> bars, int period) {
        if (bars.size() < 2) return null;
        List<WarehouseRows.PriceBar> window = tail(bars, period + 1);
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = 1; i < window.size(); i++) {
            WarehouseRows.PriceBar prev = window.get(i - 1);
            WarehouseRows.PriceBar cur = window.get(i);
            BigDecimal tr = trueRange(prev, cur);
            if (tr != null) {
                sum = sum.add(tr);
                count++;
            }
        }
        if (count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal trueRange(WarehouseRows.PriceBar prev, WarehouseRows.PriceBar cur) {
        BigDecimal prevClose = prev.closePrice();
        BigDecimal high = cur.highPrice();
        BigDecimal low = cur.lowPrice();
        if (high == null || low == null) {
            // 只有收盘价：退化成「收盘价对收盘价的绝对变动」。这是真实数据，不是插补。
            return prevClose == null ? null : cur.closePrice().subtract(prevClose).abs();
        }
        BigDecimal range = high.subtract(low);
        if (prevClose == null) return range;
        BigDecimal up = high.subtract(prevClose).abs();
        BigDecimal down = low.subtract(prevClose).abs();
        return range.max(up).max(down);
    }

    /** 给打分用的相对均线偏离（%）；均线为 null 时返回 null。 */
    public static BigDecimal distanceFrom(BigDecimal price, BigDecimal ma) {
        if (price == null || ma == null || ma.signum() == 0) return null;
        return price.subtract(ma)
                .multiply(HUNDRED)
                .divide(ma, 2, RoundingMode.HALF_UP);
    }

    /** 加权平均，P4 算综合分时用；缺失的权重不参与归一。 */
    public static BigDecimal weightedAverage(List<BigDecimal> values, List<BigDecimal> weights) {
        if (values == null || weights == null || values.size() != weights.size() || values.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null || weights.get(i) == null) continue;
            sum = sum.add(values.get(i).multiply(weights.get(i)));
            weightSum = weightSum.add(weights.get(i));
        }
        if (weightSum.signum() == 0) return null;
        return sum.divide(weightSum, MathContext.DECIMAL64);
    }
}
