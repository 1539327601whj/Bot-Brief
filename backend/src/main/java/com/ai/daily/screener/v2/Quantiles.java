package com.ai.daily.screener.v2;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 估值序列的分位数。
 *
 * <p>用**线性插值**而不是「第 n×p 个」：PE 序列的尾部很密，最近秩法会让
 * q25/q40 在两个相邻交易日之间来回跳，参考区间也跟着抖。
 *
 * <p>只接受正数（PE/PB ≤ 0 没有经济含义），调用方无需自己过滤；
 * 但仍要求调用方按日期升序传入——分位数与顺序无关，这里只是不为排序付成本。
 */
public final class Quantiles {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private Quantiles() {
    }

    /** 返回 {@code null} 表示样本不足，调用方必须按「没有估值锚」处理，不能拿 0 顶替。 */
    public static BigDecimal quantile(List<BigDecimal> values, double p) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<BigDecimal> clean = new ArrayList<>(values.size());
        for (BigDecimal value : values) {
            if (value != null && value.signum() > 0) {
                clean.add(value);
            }
        }
        if (clean.isEmpty()) {
            return null;
        }
        clean.sort(BigDecimal::compareTo);
        if (clean.size() == 1) {
            return clean.get(0);
        }
        double position = Math.max(0, Math.min(1, p)) * (clean.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(clean.size() - 1, lower + 1);
        double fraction = position - lower;
        if (fraction == 0 || lower == upper) {
            return clean.get(lower);
        }
        BigDecimal low = clean.get(lower);
        BigDecimal high = clean.get(upper);
        return low.add(high.subtract(low)
                .multiply(BigDecimal.valueOf(fraction), MC), MC)
                .stripTrailingZeros();
    }

    public static int validCount(List<BigDecimal> values) {
        if (values == null) {
            return 0;
        }
        int count = 0;
        for (BigDecimal value : values) {
            if (value != null && value.signum() > 0) {
                count++;
            }
        }
        return count;
    }
}
