package com.ai.daily.screener.v2.analysis;

import java.util.List;

/**
 * 一条结论依据或风险。
 *
 * <p>{@code evidenceIds} 指向具体指标（{@code ev:val:pe_percentile} 等）。
 * 规则文案可以没有证据编号，但**AI 改写时每一个数字都必须能落到一个编号上**，
 * 否则校验直接回退规则文案。
 */
public record Reason(String text, List<String> evidenceIds) {

    public static Reason of(String text, String... evidenceIds) {
        return new Reason(text, List.of(evidenceIds));
    }

    public static Reason plain(String text) {
        return new Reason(text, List.of());
    }
}
