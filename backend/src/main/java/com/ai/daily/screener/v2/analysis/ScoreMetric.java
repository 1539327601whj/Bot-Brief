package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;

/**
 * 一个子指标：值 + 分数 + 权重 + 依据 + 证据编号。
 *
 * <p>{@code score == null} 表示**这一项不可用**（缺数据 / 不适用），
 * 与 0 分严格区分：0 分会把结论往下拉，缺数据只会降低覆盖率。
 * 权重在归一化时只统计可用项，所以缺一两项不会让整维分数凭空变低。
 */
public record ScoreMetric(
        String key,
        String label,
        BigDecimal value,
        String display,
        BigDecimal score,
        double weight,
        String basis,
        String evidenceId
) {

    public boolean available() {
        return score != null;
    }

    public static ScoreMetric of(String key, String label, BigDecimal value, String display,
                                 BigDecimal score, double weight, String basis,
                                 String evidenceId) {
        return new ScoreMetric(key, label, value, display, score, weight, basis, evidenceId);
    }

    public static ScoreMetric unavailable(String key, String label, String basis,
                                          String evidenceId) {
        return new ScoreMetric(key, label, null, null, null, 0, basis, evidenceId);
    }
}
