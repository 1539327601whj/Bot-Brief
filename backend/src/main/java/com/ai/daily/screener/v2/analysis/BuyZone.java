package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 参考买入区间与参考止损位。
 *
 * <p>状态：
 * <ul>
 *   <li>{@code IN_ZONE}：当前价已在区间内；</li>
 *   <li>{@code ABOVE_ZONE}：当前价高于区间上沿，页面显示距上沿百分比；</li>
 *   <li>{@code BELOW_ZONE}：当前价低于区间下沿（仍然展示区间，但不写「等待回调」）；</li>
 *   <li>{@code WITHHELD}：条件不满足，不给区间，只给原因；</li>
 *   <li>{@code BROKEN}：当前价已跌破参考止损，不给新区间。</li>
 * </ul>
 *
 * <p>这些都只是**规则计算的参考位**，不是目标价，也不是买入建议。
 */
public record BuyZone(
        String status,
        BigDecimal low,
        BigDecimal high,
        BigDecimal stopLoss,
        BigDecimal distanceToZonePct,
        BigDecimal riskPct,
        List<Reason> basis,
        List<String> notes
) {

    public static BuyZone withheld(String reason) {
        return new BuyZone("WITHHELD", null, null, null, null, null, List.of(),
                List.of(reason));
    }
}
