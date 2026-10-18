package com.ai.daily.screener.v2.analysis;

import com.ai.daily.screener.v2.FactsSnapshot;
import com.ai.daily.screener.v2.Quantiles;
import com.ai.daily.screener.v2.TechnicalCalculator;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 参考区间与参考止损。
 *
 * <p>区间的两个锚必须**同时**成立：
 * <ul>
 *   <li><b>估值锚</b>：PE（或 PB）历史 q25–q40 对应的价格。用 q25/q40 而不是
 *       「历史最低」，是因为最低点只在事后才知道，q25 是当时也经常到达的位置；</li>
 *   <li><b>技术锚</b>：均线与近期低点构成的支撑带。只用当前价下方 2% 以内的候选，
 *       高于现价的「支撑」没有参考意义。</li>
 * </ul>
 *
 * <p>两个锚的交集才是区间。交集为空且差距 >5% 时宁可 {@code WITHHELD}，
 * 也不把估值下沿和技术上沿强行拼成一个看起来合理的区间——那种区间上下沿
 * 来自两套完全不同的假设，宽度没有意义。
 *
 * <p>纯函数：不查库、不读时钟。所有输入来自 {@link FactsSnapshot}。
 */
public final class BuyZoneCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final int MIN_HISTORY_POINTS = 500;
    private static final int MAX_HISTORY_YEARS = 10;
    private static final int MIN_HISTORY_YEARS = 3;
    private static final int MIN_PRICE_BARS = 250;
    private static final BigDecimal NEAR_PRICE = BigDecimal.valueOf(1.02);
    private static final BigDecimal MAX_ZONE_WIDTH = BigDecimal.valueOf(0.12);
    private static final BigDecimal MAX_GAP = BigDecimal.valueOf(0.05);
    private static final BigDecimal FLOOR_RATIO = BigDecimal.valueOf(0.75);
    private static final BigDecimal CEILING_RATIO = BigDecimal.valueOf(0.98);

    private BuyZoneCalculator() {
    }

    public static BuyZone compute(FactsSnapshot facts, ScoreResult score, LocalDate today) {
        LocalDate safeToday = today == null ? LocalDate.now() : today;
        TechnicalCalculator.TechnicalSummary technical =
                TechnicalCalculator.compute(facts.prices().data());
        BigDecimal price = firstPositive(
                facts.quote().data() == null ? null : facts.quote().data().price(),
                technical.price());
        if (price == null) {
            return BuyZone.withheld("没有当前价格，无法计算参考区间");
        }
        if (priceStale(facts, technical, safeToday)) {
            return BuyZone.withheld("价格数据超过 3 个交易日未更新，参考区间意义不足");
        }
        List<String> flags = score == null ? List.of() : score.hardFlags();
        if (flags.contains("ST_OR_DELISTING")) {
            return BuyZone.withheld("ST 或退市整理标的不给参考区间");
        }
        if (flags.contains("NEGATIVE_EQUITY")) {
            return BuyZone.withheld("净资产为负，估值锚失真，不给参考区间");
        }
        if (flags.contains("SHORT_HISTORY")) {
            return BuyZone.withheld("上市不足 250 个交易日，历史数据不足以给出参考区间");
        }
        if (score == null || score.coverage() == null
                || score.coverage().compareTo(BigDecimal.valueOf(0.50)) < 0) {
            return BuyZone.withheld("数据覆盖率不足 50%，不给参考区间");
        }
        if (technical.barCount() < MIN_PRICE_BARS) {
            return BuyZone.withheld("日线只有 " + technical.barCount()
                    + " 根，不足 " + MIN_PRICE_BARS + " 根，不给参考区间");
        }

        Anchor valuation = valuationAnchor(facts, price, safeToday);
        if (valuation == null) {
            return BuyZone.withheld("缺少可用的估值锚（PE/PB 历史不足或当前估值为负）");
        }
        Support support = support(technical, price);
        if (support == null) {
            return BuyZone.withheld("当前价下方没有可用的技术支撑位");
        }

        BigDecimal low = valuation.low().max(support.tLow());
        BigDecimal high = valuation.high().min(support.tHigh());
        List<String> notes = new ArrayList<>(valuation.notes());
        notes.addAll(support.notes());

        if (low.compareTo(high) > 0) {
            BigDecimal gap = low.subtract(high);
            if (gap.compareTo(price.multiply(MAX_GAP, MC)) <= 0) {
                BigDecimal bridgedLow = high;
                BigDecimal bridgedHigh = low;
                low = bridgedLow;
                high = bridgedHigh;
                notes.add("估值锚与支撑锚接近，取桥接区间");
            } else {
                return BuyZone.withheld("估值下沿与技术支持位相差超过 5%，两套假设不重合");
            }
        }
        if (high.subtract(low).compareTo(price.multiply(MAX_ZONE_WIDTH, MC)) > 0) {
            return BuyZone.withheld("参考区间宽度超过当前价的 12%，精度不足");
        }

        // 上沿不能高于现价 2%：给一个「已经高于现价」的参考区间没有意义。
        high = high.min(price.multiply(CEILING_RATIO, MC));
        low = low.max(price.multiply(FLOOR_RATIO, MC));
        if (low.compareTo(high) > 0) {
            return BuyZone.withheld("当前价把参考区间压缩为负宽度，暂不给出");
        }

        BigDecimal stop = stopLoss(support, low);
        BigDecimal riskPct = price.subtract(stop)
                .multiply(HUNDRED)
                .divide(price, 2, RoundingMode.HALF_UP);
        BigDecimal limit = "STOCK".equals(facts.instrument().assetType())
                ? BigDecimal.valueOf(18) : BigDecimal.valueOf(12);
        if (riskPct.compareTo(limit) > 0) {
            return BuyZone.withheld("参考止损距离当前价 " + riskPct.stripTrailingZeros()
                    + "%，超过 " + limit.stripTrailingZeros() + "% 上限，参考意义弱");
        }
        if (price.compareTo(stop) <= 0) {
            BigDecimal roundedStop = round(stop, facts.instrument().assetType());
            return new BuyZone("BROKEN", null, null, roundedStop, null, riskPct,
                    List.copyOf(valuation.basis()), List.of("当前价已跌破参考止损位，不给新区间"));
        }

        BigDecimal roundedLow = round(low, facts.instrument().assetType());
        BigDecimal roundedHigh = round(high, facts.instrument().assetType());
        BigDecimal roundedStop = round(stop, facts.instrument().assetType());

        String status;
        BigDecimal distance = null;
        if (price.compareTo(roundedLow) < 0) {
            status = "BELOW_ZONE";
            notes.add("当前价低于参考区间下沿；这不是「等待回调」的信号，而是区间假设需要复核");
        } else if (price.compareTo(roundedHigh) > 0) {
            status = "ABOVE_ZONE";
            distance = price.subtract(roundedHigh)
                    .multiply(HUNDRED)
                    .divide(roundedHigh, 2, RoundingMode.HALF_UP);
        } else {
            status = "IN_ZONE";
        }

        List<Reason> basis = new ArrayList<>(valuation.basis());
        basis.addAll(support.basis());
        notes.add("参考区间与止损位均由规则计算，不是目标价，也不构成投资建议");

        return new BuyZone(status, roundedLow, roundedHigh, roundedStop, distance, riskPct,
                List.copyOf(basis), List.copyOf(notes));
    }

    // ==================================================================
    // 估值锚
    // ==================================================================

    private record Anchor(BigDecimal low, BigDecimal high, List<Reason> basis,
                          List<String> notes) {
    }

    private static Anchor valuationAnchor(FactsSnapshot facts, BigDecimal price,
                                          LocalDate today) {
        if (facts.instrument().isStock()) {
            return stockValuationAnchor(facts.valuations().data(), price, today);
        }
        return indexValuationAnchor(facts.indexValuation().data(), price);
    }

    private static Anchor stockValuationAnchor(List<WarehouseRows.ValuationPoint> points,
                                               BigDecimal price, LocalDate today) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        LocalDate from = today.minusYears(MAX_HISTORY_YEARS);

        List<BigDecimal> peHistory = new ArrayList<>();
        LocalDate firstPeDate = null;
        LocalDate lastPeDate = null;
        WarehouseRows.ValuationPoint latest = null;
        for (WarehouseRows.ValuationPoint point : points) {
            if (point == null || point.tradeDate() == null) continue;
            latest = point;
            if (point.tradeDate().isBefore(from)) continue;
            if (point.peTtm() != null && point.peTtm().signum() > 0) {
                peHistory.add(point.peTtm());
                if (firstPeDate == null) firstPeDate = point.tradeDate();
                lastPeDate = point.tradeDate();
            }
        }
        if (latest != null && latest.peTtm() != null && latest.peTtm().signum() > 0
                && enough(peHistory, firstPeDate, lastPeDate)) {
            BigDecimal q25 = Quantiles.quantile(peHistory, 0.25);
            BigDecimal q40 = Quantiles.quantile(peHistory, 0.40);
            if (q25 != null && q40 != null && q25.signum() > 0) {
                BigDecimal low = price.multiply(q25, MC)
                        .divide(latest.peTtm(), 6, RoundingMode.HALF_UP);
                BigDecimal high = price.multiply(q40, MC)
                        .divide(latest.peTtm(), 6, RoundingMode.HALF_UP);
                if (low.signum() > 0 && high.compareTo(low) >= 0) {
                    return new Anchor(low, high,
                            List.of(Reason.of("估值锚：PE 历史 q25–q40 对应价格区间（当前 PE "
                                            + display(latest.peTtm()) + "，窗口自 "
                                            + firstPeDate + " 起）",
                                    "ev:val:pe_ttm", "ev:val:pe_percentile")),
                            List.of("估值锚用东财估值分析的同一段 PE 历史，不跨源"));
                }
            }
        }

        // PE 失效（亏损或历史不足）→ PB。仍然是同一段本地估值历史。
        List<BigDecimal> pbHistory = new ArrayList<>();
        LocalDate firstPbDate = null;
        LocalDate lastPbDate = null;
        for (WarehouseRows.ValuationPoint point : points) {
            if (point == null || point.tradeDate() == null || point.tradeDate().isBefore(from)) {
                continue;
            }
            if (point.pbMrq() != null && point.pbMrq().signum() > 0) {
                pbHistory.add(point.pbMrq());
                if (firstPbDate == null) firstPbDate = point.tradeDate();
                lastPbDate = point.tradeDate();
            }
        }
        if (latest != null && latest.pbMrq() != null && latest.pbMrq().signum() > 0
                && enough(pbHistory, firstPbDate, lastPbDate)) {
            BigDecimal q25 = Quantiles.quantile(pbHistory, 0.25);
            BigDecimal q40 = Quantiles.quantile(pbHistory, 0.40);
            if (q25 != null && q40 != null && q25.signum() > 0) {
                BigDecimal low = price.multiply(q25, MC)
                        .divide(latest.pbMrq(), 6, RoundingMode.HALF_UP);
                BigDecimal high = price.multiply(q40, MC)
                        .divide(latest.pbMrq(), 6, RoundingMode.HALF_UP);
                if (low.signum() > 0 && high.compareTo(low) >= 0) {
                    return new Anchor(low, high,
                            List.of(Reason.of("估值锚：PE 不可用，改用 PB 历史 q25–q40"
                                            + "（当前 PB " + display(latest.pbMrq())
                                            + "，窗口自 " + firstPbDate + " 起）",
                                    "ev:val:pb")),
                            List.of("当前 PE 为负或历史不足，估值锚切换到 PB"));
                }
            }
        }
        return null;
    }

    private static Anchor indexValuationAnchor(FactsSnapshot.IndexValuation valuation,
                                               BigDecimal price) {
        if (valuation == null || valuation.peTtm() == null
                || valuation.peTtm().signum() <= 0
                || valuation.peQ25() == null || valuation.peQ40() == null
                || valuation.peQ25().signum() <= 0
                || valuation.historyLength() == null
                || valuation.historyLength() < MIN_HISTORY_POINTS) {
            return null;
        }
        if (valuation.historyFrom() == null || valuation.historyTo() == null
                || ChronoUnit.YEARS.between(valuation.historyFrom(), valuation.historyTo())
                < MIN_HISTORY_YEARS) {
            return null;
        }
        BigDecimal low = price.multiply(valuation.peQ25(), MC)
                .divide(valuation.peTtm(), 6, RoundingMode.HALF_UP);
        BigDecimal high = price.multiply(valuation.peQ40(), MC)
                .divide(valuation.peTtm(), 6, RoundingMode.HALF_UP);
        if (low.signum() <= 0 || high.compareTo(low) < 0) {
            return null;
        }
        return new Anchor(low, high,
                List.of(Reason.of("估值锚：跟踪指数 PE 分位 q25–q40 反推价格"
                                + "（口径 " + valuation.percentileMethod() + "，PE "
                                + display(valuation.peTtm()) + "，窗口 "
                                + valuation.historyFrom() + " 至 " + valuation.historyTo() + "）",
                        "ev:index:pe_percentile", "ev:index:pe_median")),
                List.of("ETF/指数价格区间假设成分股盈利不变"));
    }

    private static boolean enough(List<BigDecimal> values, LocalDate from, LocalDate to) {
        if (values.size() < MIN_HISTORY_POINTS || from == null || to == null) {
            return false;
        }
        return ChronoUnit.YEARS.between(from, to) >= MIN_HISTORY_YEARS;
    }

    // ==================================================================
    // 技术锚
    // ==================================================================

    private record Support(BigDecimal s1, BigDecimal s2, BigDecimal tLow, BigDecimal tHigh,
                           BigDecimal atr, List<Reason> basis, List<String> notes) {
    }

    private static Support support(TechnicalCalculator.TechnicalSummary technical,
                                   BigDecimal price) {
        List<SupportCandidate> candidates = new ArrayList<>();
        addCandidate(candidates, "MA20", technical.ma20());
        addCandidate(candidates, "MA60", technical.ma60());
        addCandidate(candidates, "MA120", technical.ma120());
        addCandidate(candidates, "MA250", technical.ma250());
        addCandidate(candidates, "20 日低点", technical.low20());
        addCandidate(candidates, "60 日低点", technical.low60());
        addCandidate(candidates, "120 日低点", technical.low120());

        BigDecimal ceiling = price.multiply(NEAR_PRICE, MC);
        List<SupportCandidate> below = new ArrayList<>();
        for (SupportCandidate candidate : candidates) {
            if (candidate.value() != null && candidate.value().signum() > 0
                    && candidate.value().compareTo(ceiling) <= 0) {
                below.add(candidate);
            }
        }
        if (below.isEmpty()) {
            return null;
        }
        SupportCandidate s1 = below.get(0);
        for (SupportCandidate candidate : below) {
            if (candidate.value().compareTo(s1.value()) > 0) {
                s1 = candidate;
            }
        }
        BigDecimal atr = technical.atr14();
        if (atr == null || atr.signum() < 0) {
            atr = BigDecimal.ZERO;
        }

        BigDecimal s2Value = null;
        SupportCandidate s2 = null;
        BigDecimal threshold = s1.value().multiply(BigDecimal.valueOf(0.98), MC);
        for (SupportCandidate candidate : below) {
            if (candidate.value().compareTo(threshold) <= 0
                    && (s2Value == null || candidate.value().compareTo(s2Value) > 0)) {
                s2Value = candidate.value();
                s2 = candidate;
            }
        }
        if (s2Value == null) {
            s2Value = s1.value().subtract(atr);
        }

        BigDecimal tLow = s2Value.subtract(atr.multiply(BigDecimal.valueOf(0.25), MC));
        BigDecimal tHigh = s1.value().add(atr.multiply(BigDecimal.valueOf(0.25), MC));

        String anchorText = s2 == null
                ? "一级支撑 " + s1.name() + " " + display(s1.value())
                + "；二级支撑取一级下方 1 ATR"
                : "一级支撑 " + s1.name() + " " + display(s1.value())
                + "；二级支撑 " + s2.name() + " " + display(s2.value());
        List<Reason> basis = List.of(Reason.of(
                "技术锚：" + anchorText + "（ATR14 " + display(atr) + "）",
                "ev:tech:support", "ev:tech:ma250"));
        List<String> notes = new ArrayList<>();
        if (!technical.hasOhlc()) {
            notes.add("日线只有收盘价，ATR 按收盘价变动计算");
        }
        return new Support(s1.value(), s2Value, tLow, tHigh, atr, basis, notes);
    }

    private record SupportCandidate(String name, BigDecimal value) {
    }

    private static void addCandidate(List<SupportCandidate> candidates, String name,
                                     BigDecimal value) {
        if (value != null && value.signum() > 0) {
            candidates.add(new SupportCandidate(name, value));
        }
    }

    /**
     * 止损 = min(二级支撑 - 0.5 ATR, 区间下沿 × 0.95)。
     *
     * <p>取两者更低的那个：技术支撑失效后还有估值下沿兜底，但兜底也要留出
     * 5% 的缓冲，否则一个日内插针就能触发。
     */
    private static BigDecimal stopLoss(Support support, BigDecimal low) {
        BigDecimal technicalStop = support.s2()
                .subtract(support.atr().multiply(BigDecimal.valueOf(0.5), MC));
        BigDecimal structuralStop = low.multiply(BigDecimal.valueOf(0.95), MC);
        BigDecimal stop = technicalStop.min(structuralStop);
        if (stop.signum() <= 0
                || stop.compareTo(low.multiply(BigDecimal.valueOf(0.97), MC)) >= 0) {
            stop = structuralStop;
        }
        return stop;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static boolean priceStale(FactsSnapshot facts,
                                      TechnicalCalculator.TechnicalSummary technical,
                                      LocalDate today) {
        LocalDate asOf = technical.lastDate();
        if (facts.quote().data() != null && facts.quote().data().asOf() != null) {
            asOf = facts.quote().data().asOf();
        }
        if (asOf == null) {
            return true;
        }
        return tradingDaysBetween(asOf, today) > 3;
    }

    private static int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return 0;
        }
        int days = 0;
        for (LocalDate date = from.plusDays(1); !date.isAfter(to); date = date.plusDays(1)) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal second) {
        if (first != null && first.signum() > 0) return first;
        if (second != null && second.signum() > 0) return second;
        return null;
    }

    private static BigDecimal round(BigDecimal value, String assetType) {
        int scale = "ETF".equals(assetType) ? 3 : 2;
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static String display(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
