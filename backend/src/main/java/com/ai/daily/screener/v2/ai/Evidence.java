package com.ai.daily.screener.v2.ai;

import java.math.BigDecimal;

/**
 * 一条可以被引用的证据。
 *
 * <p>{@code display} 是页面与提示词里给人看的值；{@code value} 是同一件事的数值形态，
 * 校验 LLM 是否「凭空造数」时以两者为准。{@code basis} 只解释算法口径，
 * 不参与判定。
 */
public record Evidence(
        String id,
        String label,
        String display,
        BigDecimal value,
        String source,
        String asOf,
        String basis
) {
}
