package com.ai.daily.screener.v2.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 代码查询的「决策层」结果：位置、时机、行动、长期/短期。
 *
 * <p>它不是新的打分模型，而是把已经算好的六维分、结论、参考区间和技术指标
 * 翻译成用户能直接执行的四件事。**所有字段都由规则算出**，AI 只能复述，
 * 不能改标签、改分数或发明数字。
 *
 * <p>标签是封闭集合；页面不要自由拼新标签。数据不足时用 {@code INSUFFICIENT}，
 * 不用「中性」或 0 分顶替。
 */
public record Decision(
        Position position,
        Timing timing,
        Action action,
        Horizon horizon,
        /** 本标的自己的长期趋势：3/5 年年化、3 年最大回撤与是否修复。 */
        LongTerm longTerm,
        /** 历史条件收益分布：当前价格分位附近，过去之后 1 / 3 年收益如何。 */
        ConditionalReturnCalculator.Outlook outlook,
        List<Reason> reasons,
        List<Reason> risks,
        List<String> watchItems,
        String summary,
        String disclaimer
) {

    public static final String DISCLAIMER =
            "以上为规则计算的倾向与观察条件，不构成投资建议、证券推荐或买卖依据。";

    /** 一个位置轴。{@code score} 取 +1（偏低位）/ 0（中性）/ -1（偏高位）。 */
    public record Axis(
            String key,
            String label,
            String value,
            String display,
            int score,
            String status,
            String basis
    ) {
    }

    public record Position(
            String key,
            String label,
            int availableAxes,
            int score,
            List<Axis> axes,
            List<Reason> reasons
    ) {
    }

    public record Timing(
            String key,
            String label,
            List<Reason> reasons,
            List<String> triggers
    ) {
    }

    public record Action(
            String key,
            String label,
            List<String> tranchePlan,
            List<String> invalidation
    ) {
    }

    public record Horizon(
            String key,
            String label,
            BigDecimal longScore,
            BigDecimal shortScore,
            BigDecimal gap,
            String summary
    ) {
    }

    /**
     * 本标的自己的长期趋势。收益与回撤都是**百分数**（8.2 = 8.2%，42.0 = 42.0%）。
     *
     * <p>{@code key}：{@code UP}（长期向上）/ {@code FLAT_OR_DOWN}（长期走平或向下）/
     * {@code MIXED}（3 年与 5 年方向不一致）/ {@code INSUFFICIENT}。
     * 样本不足时数值为 null，不拿短样本年化冒充长期收益。
     */
    public record LongTerm(
            String key,
            String label,
            BigDecimal threeYearCagrPct,
            BigDecimal fiveYearCagrPct,
            BigDecimal threeYearMaxDrawdownPct,
            Boolean drawdownRecovered,
            String summary
    ) {
        public static LongTerm insufficient(String reason) {
            return new LongTerm("INSUFFICIENT", "长期数据不足",
                    null, null, null, null, reason);
        }
    }

    /** 数据不足时的完整决策：位置/时机/行动/周期都明确写「数据不足」，不补标签。 */
    public static Decision insufficient(String reason, List<Reason> risks, List<Axis> axes) {
        List<Reason> safeRisks = risks == null ? List.of() : List.copyOf(risks);
        List<Axis> safeAxes = axes == null ? List.of() : List.copyOf(axes);
        return new Decision(
                new Position("INSUFFICIENT", "位置数据不足", 0, 0, safeAxes,
                        List.of(Reason.plain(reason))),
                new Timing("INSUFFICIENT", "数据不足", List.of(Reason.plain(reason)),
                        List.of("先补齐估值、日线与财务数据")),
                new Action("INSUFFICIENT", "数据不足",
                        List.of("先补齐关键数据，再做判断"),
                        List.of("数据恢复后重新查询")),
                new Horizon("INSUFFICIENT", "数据不足", null, null, null, reason),
                LongTerm.insufficient("关键数据不足，长期趋势未计算"),
                ConditionalReturnCalculator.Outlook.unavailable(
                        "关键数据不足，历史条件收益未计算"),
                List.of(),
                safeRisks,
                List.of("估值分位", "一年价格分位", "日线趋势", "财务数据"),
                "当前位置、时机与长期/短期都缺少足够数据，暂不给出规则结论。",
                DISCLAIMER);
    }
}
