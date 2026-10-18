package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 分段线性打分锚点。
 *
 * <p>不用「加分/扣分」那套：加减分对阈值敏感、不可积、也没法解释。
 * 三个锚点 {@code bad / neutral / good} 把「差、中、好」直接写成数，
 * 曲线连续，单测只需要钉住三档和两个边界。
 *
 * <p>返回值均为 0–100 的两位小数；输入为 {@code null} 时返回 {@code null}，
 * 让上层把它算成「这一项不可用」而不是 0 分——0 分是一个结论，缺数据不是。
 */
public final class ScoreAnchors {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal FIFTY = BigDecimal.valueOf(50);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ScoreAnchors() {
    }

    /** 高值好：{@code bad} 以下 0 分，{@code good} 以上 100 分，中间分两段线性。 */
    public static BigDecimal higherBetter(BigDecimal value, double bad, double neutral,
                                          double good) {
        if (value == null) {
            return null;
        }
        if (bad == good) {
            return value.doubleValue() >= good ? HUNDRED.setScale(2) : ZERO.setScale(2);
        }
        double x = value.doubleValue();
        double low = Math.min(bad, good);
        double high = Math.max(bad, good);
        // 支持 bad > good 的写法（例如阈值写成上界），但语义仍是「越大越好」。
        if (bad > good) {
            double t = low;
            low = high;
            high = t;
        }
        if (x <= low) {
            return ZERO.setScale(2);
        }
        if (x >= high) {
            return HUNDRED.setScale(2);
        }
        double score;
        if (x <= neutral) {
            score = 50.0 * (x - low) / (neutral - low);
        } else {
            score = 50.0 + 50.0 * (x - neutral) / (high - neutral);
        }
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 低值好：{@code good} 以下 100 分，{@code bad} 以上 0 分。
     *
     * <p>三个锚点通常满足 {@code good < neutral < bad}（例如 PE 12 / 30 / 60）。
     * 顺序写反时自动交换，避免调用方因为一个笔误把分数整体翻过来。
     */
    public static BigDecimal lowerBetter(BigDecimal value, double bad, double neutral,
                                         double good) {
        if (value == null) {
            return null;
        }
        double low = Math.min(good, bad);
        double high = Math.max(good, bad);
        double x = value.doubleValue();
        if (x <= low) {
            return HUNDRED.setScale(2);
        }
        if (x >= high) {
            return ZERO.setScale(2);
        }
        double score;
        if (x <= neutral) {
            double span = neutral - low;
            score = span == 0 ? 100 : 50.0 + 50.0 * (neutral - x) / span;
        } else {
            double span = high - neutral;
            score = span == 0 ? 0 : 50.0 * (high - x) / span;
        }
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 带状最优：区间 {@code [bestLow, bestHigh]} 内满分，两侧线性递减，出界为 0。
     *
     * <p>换手率这类指标不是「越高越好」也不是「越低越好」，而是一个区间——
     * 太低说明没人交易，太高说明筹码在短线资金手里。
     */
    public static BigDecimal band(BigDecimal value, double outerLow, double bestLow,
                                  double bestHigh, double outerHigh) {
        if (value == null) {
            return null;
        }
        double x = value.doubleValue();
        if (x >= bestLow && x <= bestHigh) {
            return HUNDRED.setScale(2);
        }
        double score;
        if (x < bestLow) {
            if (x <= outerLow) {
                return ZERO.setScale(2);
            }
            score = 100.0 * (x - outerLow) / (bestLow - outerLow);
        } else {
            if (x >= outerHigh) {
                return ZERO.setScale(2);
            }
            score = 100.0 * (outerHigh - x) / (outerHigh - bestHigh);
        }
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    /** 把 0–100 的分数钳到边界，供事件调整等少数会越界的场景使用。 */
    public static BigDecimal clamp(BigDecimal score) {
        if (score == null) {
            return null;
        }
        if (score.signum() < 0) {
            return ZERO.setScale(2);
        }
        if (score.compareTo(HUNDRED) > 0) {
            return HUNDRED.setScale(2);
        }
        return score;
    }

    static BigDecimal fifty() {
        return FIFTY.setScale(2);
    }
}
