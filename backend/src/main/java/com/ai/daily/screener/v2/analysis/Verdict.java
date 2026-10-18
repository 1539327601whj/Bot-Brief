package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 规则结论。
 *
 * <p>{@code withheldReason} 非空时不显示分数与标签——数据不足就是数据不足，
 * 不给一个看起来精确的数字让人误读。
 */
public record Verdict(
        String label,
        String labelKey,
        BigDecimal score,
        BigDecimal scoreLow,
        BigDecimal scoreHigh,
        BigDecimal coverage,
        BigDecimal confidence,
        BigDecimal eventAdjustment,
        List<String> hardFlags,
        List<Reason> reasons,
        List<Reason> risks,
        String withheldReason
) {

    public boolean withheld() {
        return withheldReason != null && !withheldReason.isBlank();
    }

    /**
     * 数据不足时的结论：**不给分数、不给标签**，只保留风险和原因。
     * 这里刻意不传 {@code null} 之外的值——页面拿到一个 0 分会把它当成「极差」。
     */
    public static Verdict withheld(ScoreResult score, BigDecimal eventAdjustment,
                                   List<String> hardFlags, List<Reason> risks,
                                   String reason) {
        return new Verdict("数据不足，暂不评级", "INSUFFICIENT", null, null, null,
                score == null ? null : score.coverage(),
                score == null ? null : score.confidence(),
                eventAdjustment == null ? BigDecimal.ZERO : eventAdjustment,
                hardFlags == null ? List.of() : List.copyOf(hardFlags),
                List.of(), risks == null ? List.of() : List.copyOf(risks), reason);
    }
}
