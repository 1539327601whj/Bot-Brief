package com.ai.daily.screener.v2.analysis;

import java.util.List;

/**
 * 条件推演卡。
 *
 * <p><b>不显示概率、不给目标价、不算隐含涨幅</b>。免费源没有可靠的盈利预测，
 * 外推出来的数字看起来精确、实际不可信。情景卡回答的是「往哪个方向走、
 * 看什么信号验证、什么情况下这个判断失效」。
 */
public record Scenario(
        String key,
        String label,
        boolean triggered,
        String condition,
        String narrative,
        List<String> evidenceIds,
        String invalidation,
        String disclaimer
) {

    public static final String DISCLAIMER = "条件推演，不是预测，不含概率与目标价。";
}
