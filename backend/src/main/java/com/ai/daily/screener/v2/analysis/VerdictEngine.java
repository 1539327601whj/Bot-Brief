package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把六维分数变成一句明确结论：标签、分数区间、依据、风险、封顶原因。
 *
 * <p>标签不是「建议」，而是**当前数据下的规则结论**。封顶规则优先于分数：
 * ST、净资产为负、当前亏损、流动性不足都直接压住标签，不允许靠其它维度的高分
 * 把一只 ST 股抬成「值得重点关注」。
 *
 * <p>事件调整只统计**近 30 天的交易所公告**（{@code category=NOTICE}）。新闻标题
 * 不参与，因为免费源的新闻标题噪声太大，一条「某公司回应传闻」就能改分。
 * 公告标题按关键词粗分类，调整值封顶 ±5；重大负面公告另把结论压到「偏谨慎」。
 *
 * <p>纯函数：不读时钟、不查库。{@code today} 由调用方传入。
 */
public final class VerdictEngine {

    private static final int EVENT_WINDOW_DAYS = 30;
    private static final int SENTIMENT_CAP = 5;

    private VerdictEngine() {
    }

    public static Verdict evaluate(FactsSnapshot facts, ScoreResult score, LocalDate today) {
        LocalDate safeToday = today == null ? LocalDate.now() : today;
        List<String> flags = score == null ? List.of() : score.hardFlags();

        EventVerdict events = events(facts == null ? null : facts.events(), safeToday);
        List<Reason> risks = new ArrayList<>(events.negativeReasons());

        if (score == null || score.finalScore() == null) {
            return Verdict.withheld(score, events.adjustment(), flags, risks,
                    "没有足够的可用数据形成评分");
        }
        if (flags.contains("PRICE_STALE")) {
            return Verdict.withheld(score, events.adjustment(), flags, risks,
                    "停牌或价格数据陈旧（超过 5 个交易日），不给出规则结论");
        }
        if (score.coverage() == null || score.coverage().compareTo(BigDecimal.valueOf(0.50)) < 0) {
            return Verdict.withheld(score, events.adjustment(), flags, risks,
                    "数据覆盖率不足 50%，暂不评级");
        }

        BigDecimal adjusted = ScoreAnchors.clamp(
                score.finalScore().add(events.adjustment()));
        BigDecimal cap = cap(flags, events.severeNegative());
        BigDecimal finalScore = adjusted;
        BigDecimal low = adjusted(score.scoreLow(), events.adjustment());
        BigDecimal high = adjusted(score.scoreHigh(), events.adjustment());
        if (cap != null) {
            finalScore = finalScore.min(cap);
            low = low == null ? null : low.min(cap);
            high = high == null ? null : high.min(cap);
        }
        if (low != null && low.compareTo(finalScore) > 0) {
            low = finalScore;
        }
        if (high != null && high.compareTo(finalScore) < 0) {
            high = finalScore;
        }

        Label label = label(finalScore);
        List<Reason> reasons = new ArrayList<>(topReasons(score));
        if (events.adjustment().signum() != 0) {
            reasons.add(Reason.of("近 30 天公告事件调整 "
                            + signed(events.adjustment()) + " 分",
                    events.evidenceIds().toArray(new String[0])));
        }
        risks.addAll(bottomRisks(score));
        risks.addAll(flagRisks(flags));
        if (score.coverage() != null
                && score.coverage().compareTo(BigDecimal.valueOf(0.70)) < 0) {
            risks.add(Reason.plain("数据覆盖率 " + display(score.coverage()) + "%，"
                    + "结论主要反映已拿到的部分"));
        }
        for (String note : score.notes()) {
            if (note != null && !note.isBlank()) {
                risks.add(Reason.plain(note));
            }
        }

        return new Verdict(label.label(), label.key(), finalScore.setScale(2, RoundingMode.HALF_UP),
                low == null ? null : low.setScale(2, RoundingMode.HALF_UP),
                high == null ? null : high.setScale(2, RoundingMode.HALF_UP),
                score.coverage(), score.confidence(), events.adjustment(), flags,
                List.copyOf(reasons), List.copyOf(risks), null);
    }

    // ==================================================================
    // 标签与封顶
    // ==================================================================

    public record Label(String key, String label) {
    }

    static Label label(BigDecimal score) {
        double value = score == null ? 0 : score.doubleValue();
        if (value >= 80) return new Label("FOCUS", "值得重点关注");
        if (value >= 65) return new Label("ACCUMULATE", "可分批关注");
        if (value >= 50) return new Label("WATCH", "观察等待");
        if (value >= 35) return new Label("CAUTIOUS", "偏谨慎");
        return new Label("AVOID", "回避");
    }

    /** 封顶值；{@code null} 表示不封顶。 */
    static BigDecimal cap(List<String> flags, boolean severeNegativeEvent) {
        BigDecimal cap = null;
        if (flags.contains("ST_OR_DELISTING") || flags.contains("NEGATIVE_EQUITY")) {
            cap = BigDecimal.valueOf(15);
        }
        if (flags.contains("LOSS_MAKING")) {
            cap = min(cap, BigDecimal.valueOf(45));
        }
        if (flags.contains("LOW_LIQUIDITY")) {
            cap = min(cap, BigDecimal.valueOf(55));
        }
        if (severeNegativeEvent) {
            cap = min(cap, BigDecimal.valueOf(49));
        }
        return cap;
    }

    private static BigDecimal min(BigDecimal first, BigDecimal second) {
        if (first == null) return second;
        return first.min(second);
    }

    private static BigDecimal adjusted(BigDecimal value, BigDecimal adjustment) {
        return value == null ? null : ScoreAnchors.clamp(value.add(adjustment));
    }

    // ==================================================================
    // 依据与风险
    // ==================================================================

    private static List<Reason> topReasons(ScoreResult score) {
        List<DimensionScore> ranked = new ArrayList<>();
        for (DimensionScore dimension : score.dimensions()) {
            if (dimension.available() && dimension.score() != null
                    && dimension.score().compareTo(BigDecimal.valueOf(60)) >= 0) {
                ranked.add(dimension);
            }
        }
        ranked.sort(Comparator.comparing(DimensionScore::score).reversed());
        List<Reason> reasons = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            reasons.add(Reason.of(describe(ranked.get(i), false),
                    evidenceIds(ranked.get(i)).toArray(new String[0])));
        }
        return reasons;
    }

    private static List<Reason> bottomRisks(ScoreResult score) {
        List<DimensionScore> ranked = new ArrayList<>();
        for (DimensionScore dimension : score.dimensions()) {
            if (dimension.available() && dimension.score() != null
                    && dimension.score().compareTo(BigDecimal.valueOf(45)) < 0) {
                ranked.add(dimension);
            }
        }
        ranked.sort(Comparator.comparing(DimensionScore::score));
        List<Reason> risks = new ArrayList<>();
        for (int i = 0; i < Math.min(2, ranked.size()); i++) {
            risks.add(Reason.of(describe(ranked.get(i), true),
                    evidenceIds(ranked.get(i)).toArray(new String[0])));
        }
        return risks;
    }

    private static String describe(DimensionScore dimension, boolean weak) {
        StringBuilder text = new StringBuilder("「").append(dimension.label())
                .append("」").append(display(dimension.score())).append(" 分");
        if (weak) {
            text.append("，偏弱");
        }
        text.append("：");
        List<String> parts = new ArrayList<>();
        for (ScoreMetric metric : dimension.metrics()) {
            if (!metric.available()) continue;
            parts.add(metric.label() + " " + (metric.display() == null
                    ? display(metric.value()) : metric.display()));
            if (parts.size() >= 2) break;
        }
        text.append(parts.isEmpty() ? "主要子项数据不足" : String.join("；", parts));
        return text.toString();
    }

    private static List<String> evidenceIds(DimensionScore dimension) {
        List<String> ids = new ArrayList<>();
        for (ScoreMetric metric : dimension.metrics()) {
            if (metric.available() && metric.evidenceId() != null) {
                ids.add(metric.evidenceId());
            }
        }
        return ids;
    }

    private static List<Reason> flagRisks(List<String> flags) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("ST_OR_DELISTING", "ST 或退市整理标的，结论按章程封顶");
        labels.put("NEGATIVE_EQUITY", "净资产为负，结论按章程封顶");
        labels.put("LOSS_MAKING", "PE(TTM) ≤ 0（当前亏损），结论按章程封顶");
        labels.put("LOW_LIQUIDITY", "成交额低于 5000 万，流动性不足，结论按章程封顶");
        labels.put("PRICE_STALE", "价格数据超过 5 个交易日未更新");
        labels.put("SHORT_HISTORY", "上市不足 120 个交易日，历史数据不足以支撑长期分位");
        List<Reason> risks = new ArrayList<>();
        for (String flag : flags) {
            String text = labels.get(flag);
            if (text != null) {
                risks.add(Reason.plain(text));
            }
        }
        return risks;
    }

    // ==================================================================
    // 公告事件调整
    // ==================================================================

    private record EventVerdict(BigDecimal adjustment, boolean severeNegative,
                                List<String> evidenceIds, List<Reason> negativeReasons) {
        static EventVerdict none() {
            return new EventVerdict(BigDecimal.ZERO, false, List.of(), List.of());
        }
    }

    private static final List<String> SEVERE_NEGATIVE = List.of(
            "立案调查", "审计非标", "保留意见", "无法表示意见", "商誉减值",
            "退市风险", "终止上市", "资金占用", "违规担保");
    private static final List<String> NEGATIVE = List.of(
            "减持", "质押", "冻结", "诉讼", "仲裁", "处罚", "问询", "警示函",
            "业绩预减", "预亏", "亏损", "解禁", "违约", "下修");
    private static final List<String> POSITIVE = List.of(
            "回购", "增持", "中标", "重大合同", "业绩预增", "预增", "扭亏",
            "分红", "权益分派", "获批", "投产", "签署");

    private static EventVerdict events(FactsSnapshot.Domain<List<WarehouseRows.Event>> domain,
                                       LocalDate today) {
        if (domain == null || domain.data() == null || domain.data().isEmpty()) {
            return EventVerdict.none();
        }
        int adjustment = 0;
        boolean severe = false;
        List<String> evidence = new ArrayList<>();
        List<Reason> negatives = new ArrayList<>();
        Map<String, Boolean> seenTitles = new LinkedHashMap<>();

        for (WarehouseRows.Event event : domain.data()) {
            if (event == null || event.publishedAt() == null || event.title() == null) continue;
            if (event.publishedAt().toLocalDate().isBefore(today.minusDays(EVENT_WINDOW_DAYS))) {
                continue;
            }
            if (!isNotice(event)) continue;
            String key = event.title().strip();
            if (key.isEmpty() || seenTitles.putIfAbsent(key, Boolean.TRUE) != null) continue;

            String title = key.toLowerCase(Locale.ROOT);
            String evidenceId = evidenceId(event);
            if (matches(title, SEVERE_NEGATIVE)) {
                adjustment -= 2;
                severe = true;
                evidence.add(evidenceId);
                negatives.add(Reason.of("公告《" + key + "》：重大负面事件，结论封顶",
                        evidenceId));
            } else if (matches(title, NEGATIVE)) {
                adjustment -= 1;
                evidence.add(evidenceId);
                negatives.add(Reason.of("公告《" + key + "》：负面事件", evidenceId));
            } else if (matches(title, POSITIVE)) {
                adjustment += 1;
                evidence.add(evidenceId);
            }
        }

        int capped = Math.max(-SENTIMENT_CAP, Math.min(SENTIMENT_CAP, adjustment));
        return new EventVerdict(BigDecimal.valueOf(capped), severe,
                List.copyOf(evidence), List.copyOf(negatives));
    }

    private static boolean isNotice(WarehouseRows.Event event) {
        String category = event.category();
        return category != null
                && ("NOTICE".equalsIgnoreCase(category)
                || "ANNOUNCEMENT".equalsIgnoreCase(category)
                || category.contains("公告"));
    }

    private static boolean matches(String lowerTitle, List<String> keywords) {
        for (String keyword : keywords) {
            if (lowerTitle.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String evidenceId(WarehouseRows.Event event) {
        if (event.contentHash() != null && event.contentHash().length() >= 8) {
            return "ev:event:" + event.contentHash().substring(0, 8);
        }
        String title = event.title() == null ? "" : event.title();
        return "ev:event:" + Integer.toHexString(title.hashCode());
    }

    // ==================================================================
    // 展示小工具
    // ==================================================================

    private static String display(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private static String signed(BigDecimal value) {
        return (value.signum() > 0 ? "+" : "") + display(value);
    }
}
