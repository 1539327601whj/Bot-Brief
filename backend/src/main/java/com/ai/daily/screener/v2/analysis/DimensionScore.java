package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 一个评分维度。
 *
 * @param weight   该类资产下的维度权重（六维合计 100）
 * @param score    可用子指标的加权平均；全部不可用时为 null
 * @param coverage 可用子指标权重 / 子指标总权重；维度整体不适用时为 0
 */
public record DimensionScore(
        String key,
        String label,
        int weight,
        BigDecimal score,
        BigDecimal coverage,
        List<ScoreMetric> metrics,
        List<String> reasons
) {

    /** OK / PARTIAL / N/A —— 页面不需要知道内部枚举，只要这三个词。 */
    public String status() {
        if (score == null) {
            return "NA";
        }
        return coverage != null && coverage.compareTo(BigDecimal.valueOf(0.999)) >= 0
                ? "OK" : "PARTIAL";
    }

    public boolean available() {
        return score != null;
    }

    public static DimensionScore of(String key, String label, int weight,
                                    List<ScoreMetric> metrics, List<String> reasons) {
        double total = 0;
        double availableWeight = 0;
        BigDecimal sum = BigDecimal.ZERO;
        for (ScoreMetric metric : metrics) {
            total += metric.weight();
            if (metric.available()) {
                availableWeight += metric.weight();
                sum = sum.add(metric.score().multiply(BigDecimal.valueOf(metric.weight())));
            }
        }
        if (availableWeight == 0) {
            return new DimensionScore(key, label, weight, null, BigDecimal.ZERO,
                    List.copyOf(metrics), List.copyOf(reasons));
        }
        BigDecimal score = sum.divide(BigDecimal.valueOf(availableWeight), 2,
                RoundingMode.HALF_UP);
        BigDecimal coverage = BigDecimal.valueOf(availableWeight)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        return new DimensionScore(key, label, weight, score, coverage,
                List.copyOf(metrics), List.copyOf(reasons));
    }
}
