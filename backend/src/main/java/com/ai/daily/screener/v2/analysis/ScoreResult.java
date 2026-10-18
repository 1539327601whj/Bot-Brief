package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 六维评分与覆盖率。
 *
 * <p>{@code scoreLow}/{@code scoreHigh} 是**缺失维度的边界值**：
 * 下界把缺失当 0 分，上界把缺失当 100 分。页面显示「62（区间 48–76）」，
 * 让人一眼看出「这个分数有多依赖还没拿到的数据」。
 */
public record ScoreResult(
        String assetType,
        String sectorProfile,
        BigDecimal rawScore,
        BigDecimal finalScore,
        BigDecimal scoreLow,
        BigDecimal scoreHigh,
        BigDecimal coverage,
        BigDecimal confidence,
        List<DimensionScore> dimensions,
        List<String> hardFlags,
        List<String> notes
) {

    public DimensionScore dimension(String key) {
        for (DimensionScore dimension : dimensions) {
            if (dimension.key().equals(key)) {
                return dimension;
            }
        }
        return null;
    }

    public BigDecimal dimensionScore(String key) {
        DimensionScore dimension = dimension(key);
        return dimension == null ? null : dimension.score();
    }
}
