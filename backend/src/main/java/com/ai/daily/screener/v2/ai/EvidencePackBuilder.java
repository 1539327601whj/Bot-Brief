package com.ai.daily.screener.v2.ai;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.screener.v2.analysis.AnalysisResult;
import com.ai.daily.screener.v2.analysis.BuyZone;
import com.ai.daily.screener.v2.analysis.DimensionScore;
import com.ai.daily.screener.v2.analysis.Reason;
import com.ai.daily.screener.v2.analysis.Scenario;
import com.ai.daily.screener.v2.analysis.ScoreMetric;
import com.ai.daily.screener.v2.analysis.Verdict;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 把已经算好的规则结果编号成证据包。
 *
 * <p><b>只搬运，不计算新的数字</b>：这里出现的每个 {@link Evidence#value()} 都来自
 * P4 的评分/区间/情景产物，或者快照里的原始字段。这样「页面上的数」与
 * 「模型看到的数」只可能差在措辞上，不可能差在数值上。
 *
 * <p>同一个编号出现两次时保留第一次（维度指标优先于区间依据），
 * 避免两处同名指标的口径说明互相覆盖。
 */
public final class EvidencePackBuilder {

    private EvidencePackBuilder() {
    }

    public static EvidencePack build(FactsSnapshot facts, AnalysisResult analysis) {
        Map<String, Evidence> items = new LinkedHashMap<>();

        for (DimensionScore dimension : analysis.score().dimensions()) {
            for (ScoreMetric metric : dimension.metrics()) {
                add(items, new Evidence(metric.evidenceId(), metric.label(),
                        metric.display() == null ? "未获取" : metric.display(),
                        metric.value(), sourceFor(facts, metric.evidenceId()),
                        asOfFor(facts, metric.evidenceId()),
                        metric.basis() == null ? dimension.label() : metric.basis()));
            }
        }

        Verdict verdict = analysis.verdict();
        add(items, new Evidence("ev:verdict:score", "综合得分",
                verdict.score() == null ? "未评级" : plain(verdict.score()),
                verdict.score(), "规则计算", dateOf(facts),
                coverageBasis(verdict)));
        add(items, new Evidence("ev:verdict:score_low", "分数区间下界",
                verdict.scoreLow() == null ? "未评级" : plain(verdict.scoreLow()),
                verdict.scoreLow(), "规则计算", dateOf(facts),
                "缺失维度按下界 0 计时的加权分"));
        add(items, new Evidence("ev:verdict:score_high", "分数区间上界",
                verdict.scoreHigh() == null ? "未评级" : plain(verdict.scoreHigh()),
                verdict.scoreHigh(), "规则计算", dateOf(facts),
                "缺失维度按上界 100 计时的加权分"));
        add(items, new Evidence("ev:verdict:coverage", "数据覆盖率",
                verdict.coverage() == null ? "未知" : plain(verdict.coverage()),
                verdict.coverage(), "规则计算", dateOf(facts),
                "可用子指标权重 ÷ 全部子指标权重"));
        if (verdict.eventAdjustment() != null
                && verdict.eventAdjustment().compareTo(BigDecimal.ZERO) != 0) {
            add(items, new Evidence("ev:verdict:event_adjustment", "事件调整",
                    plain(verdict.eventAdjustment()), verdict.eventAdjustment(),
                    "公告/事件规则", dateOf(facts), "只统计近 30 天高置信度公告，调整上限 ±5"));
        }

        BuyZone zone = analysis.buyZone();
        if (zone.low() != null) {
            add(items, zoneEvidence("ev:buyzone:low", "参考区间下沿", zone.low(), facts));
        }
        if (zone.high() != null) {
            add(items, zoneEvidence("ev:buyzone:high", "参考区间上沿", zone.high(), facts));
        }
        if (zone.stopLoss() != null) {
            add(items, zoneEvidence("ev:buyzone:stop", "参考止损位", zone.stopLoss(), facts));
        }

        FactsSnapshot.Quote quote = facts.quote().data();
        if (quote != null && quote.price() != null) {
            add(items, new Evidence("ev:quote:price", "现价", plain(quote.price()),
                    quote.price(), quote.providerLabel(),
                    quote.asOf() == null ? facts.quote().asOf() : quote.asOf().toString(),
                    "行情快照价，不是复权收盘价"));
        }

        addEventEvidence(items, facts, verdict.reasons());
        addEventEvidence(items, facts, verdict.risks());
        for (Scenario scenario : analysis.scenarios()) {
            for (String id : scenario.evidenceIds()) {
                addEventEvidence(items, facts, id);
            }
        }

        String subject = facts.identity().name() == null
                ? facts.instrument().key()
                : facts.identity().name() + "（" + facts.instrument().key() + "）";
        return new EvidencePack(subject, facts.snapshotId(), analysis.rulesVersion(),
                List.copyOf(items.values()));
    }

    private static Evidence zoneEvidence(String id, String label, BigDecimal value,
                                         FactsSnapshot facts) {
        return new Evidence(id, label, plain(value), value,
                sourceFor(facts, "ev:val:"), asOfFor(facts, "ev:val:"),
                "由估值分位与技术支撑合成，规则计算，不是目标价");
    }

    private static String coverageBasis(Verdict verdict) {
        if (verdict.coverage() == null) {
            return "六维加权；覆盖率未知";
        }
        return "六维加权后按覆盖率收缩；覆盖率 " + plain(verdict.coverage())
                + (verdict.confidence() == null ? "" : "，置信度 " + plain(verdict.confidence()));
    }

    private static void addEventEvidence(Map<String, Evidence> items, FactsSnapshot facts,
                                         List<Reason> reasons) {
        for (Reason reason : reasons) {
            for (String id : reason.evidenceIds()) {
                addEventEvidence(items, facts, id);
            }
        }
    }

    private static void addEventEvidence(Map<String, Evidence> items, FactsSnapshot facts,
                                         String id) {
        if (id == null || !id.startsWith("ev:event:") || items.containsKey(id)) {
            return;
        }
        String suffix = id.substring("ev:event:".length());
        List<WarehouseRows.Event> events = facts.events().data();
        if (events == null) {
            return;
        }
        events.stream()
                .filter(matchesEvent(suffix))
                .findFirst()
                .ifPresent(event -> add(items, new Evidence(id,
                        event.title() == null ? "公告/事件" : event.title(),
                        event.eventType() == null ? "事件" : event.eventType(),
                        null, event.source(),
                        event.publishedAt() == null ? null
                                : event.publishedAt().toLocalDate().toString(),
                        event.summary() == null ? "公告标题，未取正文" : event.summary())));
    }

    private static Predicate<WarehouseRows.Event> matchesEvent(String suffix) {
        return event -> {
            String hash = event.contentHash();
            if (hash != null && hash.startsWith(suffix)) {
                return true;
            }
            String title = event.title() == null ? "" : event.title();
            return Integer.toHexString(title.hashCode()).equals(suffix);
        };
    }

    private static void add(Map<String, Evidence> items, Evidence evidence) {
        if (evidence.id() == null || evidence.id().isBlank()) {
            return;
        }
        items.putIfAbsent(evidence.id(), evidence);
    }

    // ------------------------------------------------------------------
    // 来源与数据日
    // ------------------------------------------------------------------

    /**
     * 证据编号的前缀决定它来自哪个域。前缀不是装饰：同一个 PE 数字，
     * 「东财估值分析 2026-10-16」与「中证官网 2026-09-30」不能互相顶替。
     */
    static String sourceFor(FactsSnapshot facts, String id) {
        if (id == null) {
            return null;
        }
        if (id.startsWith("ev:quote:")) {
            FactsSnapshot.Quote quote = facts.quote().data();
            return quote == null ? null : quote.providerLabel();
        }
        if (id.startsWith("ev:tech:")) {
            return latestPriceSource(facts);
        }
        if (id.startsWith("ev:grow:") || id.startsWith("ev:qual:")
                || id.startsWith("ev:health:")) {
            return latestFinancialSource(facts);
        }
        if (id.startsWith("ev:index:")) {
            FactsSnapshot.IndexValuation valuation = facts.indexValuation().data();
            return valuation == null ? null : valuation.sourceLabel();
        }
        if (id.startsWith("ev:fund:")) {
            FactsSnapshot.FundProfile profile = facts.fundProfile() == null
                    ? null : facts.fundProfile().data();
            return profile == null ? null : profile.source();
        }
        if (id.startsWith("ev:val:") || id.startsWith("ev:peer:")) {
            return stockValuationSource(facts);
        }
        return "规则计算";
    }

    static String asOfFor(FactsSnapshot facts, String id) {
        if (id == null) {
            return null;
        }
        if (id.startsWith("ev:quote:")) {
            FactsSnapshot.Quote quote = facts.quote().data();
            if (quote != null && quote.asOf() != null) {
                return quote.asOf().toString();
            }
            return facts.quote().asOf();
        }
        if (id.startsWith("ev:tech:")) {
            return facts.prices().asOf();
        }
        if (id.startsWith("ev:grow:") || id.startsWith("ev:qual:")
                || id.startsWith("ev:health:")) {
            return facts.financials().asOf();
        }
        if (id.startsWith("ev:index:")) {
            return facts.indexValuation().asOf();
        }
        if (id.startsWith("ev:fund:")) {
            FactsSnapshot.FundProfile profile = facts.fundProfile() == null
                    ? null : facts.fundProfile().data();
            if (profile == null) {
                return null;
            }
            // 规模与跟踪误差是两张页面、两个数据日，不能共用一个 asOf。
            String suffix = id.substring("ev:fund:".length());
            return switch (suffix) {
                case "aum" -> profile.aumAsOf() == null ? null : profile.aumAsOf().toString();
                case "tracking" -> profile.trackingErrorAsOf() == null
                        ? null : profile.trackingErrorAsOf().toString();
                default -> facts.fundProfile().asOf();
            };
        }
        if (id.startsWith("ev:val:") || id.startsWith("ev:peer:")) {
            return facts.instrument().isStock()
                    ? facts.valuations().asOf() : facts.indexValuation().asOf();
        }
        return dateOf(facts);
    }

    private static String stockValuationSource(FactsSnapshot facts) {
        if (!facts.instrument().isStock()) {
            FactsSnapshot.IndexValuation valuation = facts.indexValuation().data();
            return valuation == null ? null : valuation.sourceLabel();
        }
        List<WarehouseRows.ValuationPoint> points = facts.valuations().data();
        if (points == null || points.isEmpty()) {
            return null;
        }
        return points.get(points.size() - 1).source();
    }

    private static String latestPriceSource(FactsSnapshot facts) {
        List<WarehouseRows.PriceBar> bars = facts.prices().data();
        if (bars == null || bars.isEmpty()) {
            return null;
        }
        return bars.get(bars.size() - 1).source();
    }

    private static String latestFinancialSource(FactsSnapshot facts) {
        List<WarehouseRows.FinancialReport> reports = facts.financials().data();
        if (reports == null || reports.isEmpty()) {
            return null;
        }
        return reports.get(0).source();
    }

    private static String dateOf(FactsSnapshot facts) {
        String prices = facts.prices().asOf();
        if (prices != null) {
            return prices;
        }
        String quote = facts.quote().asOf();
        return quote != null ? quote : facts.snapshotAt();
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
