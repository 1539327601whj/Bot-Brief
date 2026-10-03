package com.ai.daily.screener;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「今 51｜昨 50 ↑ +0.52%｜周 51 ↑ +0.08%｜… ｜五年 …｜十年 …」这一行的算法。
 *
 * <p><b>逐条对齐 {@code automation/scripts/etf_report.py} 的既有口径</b>，因为这一行就是
 * 用户已经看惯了的那一行，数字对不上是会被立刻发现的。对齐的是三件事：
 *
 * <ol>
 *   <li><b>基线怎么取</b>：对每个目标日做「在该日**或之前**的最近一次观测」，
 *       且距目标日**不超过 {@value #STALE_DAYS} 天</b>（{@code pe_observation_on_or_before}）。
 *       超出这个宽度就不认——否则停牌股的「周」会悄悄变成三个月前的那一天，
 *       而页面上看不出来。</li>
 *   <li><b>目标日怎么算</b>：{@code 昨}=前 1 天、{@code 周}=前 7 天，
 *       {@code 月}/{@code 半年} 走**日历月**回退（月末夹取：3/31 回退一月是 2/28），
 *       {@code 一年}及更长走**日历年**回退（2/29 回退落 2/28）。
 *       这些与 Java 的 {@link LocalDate#minusMonths}/{@link LocalDate#minusYears}
 *       行为一致，所以直接用它，别再手写一遍。</li>
 *   <li><b>变化怎么算</b>：价格是相对涨幅 {@code (今/基线 - 1) × 100}；分位是**百分点差**
 *       {@code 今 - 基线}。两者不能互换——分位本身已经是百分数，
 *       再算一次相对涨幅会把「51 比 50 高 1 个百分点」说成「高 2%」。</li>
 * </ol>
 *
 * <p><b>这一行既用于价格也用于分位</b>，所以类名不叫 Prices…：输入就是一段
 * {@code (日期, 值)} 的序列，值可以是收盘价也可以是分位。差别只在 {@link ChangeStyle}。
 *
 * <p><b>八档都取不到就说清为什么</b>（章程 §6）：历史太短、当日附近没有观测、
 * 来源本身不提供，是三种不同的处置。缺档一律给出 {@link Cell#status()}，
 * **绝不返回 0 或一个裸破折号**——那两种在页面上和「真的是 0」分不出来。
 *
 * <p>无网络、无 Spring 依赖，可直接单测，见 {@code LookbackCalculatorTest}。
 */
public final class LookbackCalculator {

    /** 基线距目标日最多这么宽。与 {@code etf_report.PE_LOOKBACK_DAYS} 是同一个数。 */
    public static final int STALE_DAYS = 15;

    /**
     * 八档，顺序固定。前六档与 ETF 日报一致，{@code 五年}/{@code 十年} 是本页新增的。
     * <b>顺序即版式</b>：改这里会一起改掉页面上那一行的顺序。
     */
    public static final List<String> LABELS =
            List.of("昨", "周", "月", "半年", "一年", "三年", "五年", "十年");

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    /** 序列上的一点。{@code value} 是收盘价或分位，取决于调用方。 */
    public record Point(LocalDate date, BigDecimal value) {}

    /** 变化的算法。见类注释第 3 条：两者不能互换。 */
    public enum ChangeStyle {
        /** 价格：相对涨幅 %，{@code (今/基线 - 1) × 100}。 */
        PRICE,
        /** 分位：百分点差，{@code 今 - 基线}。 */
        POINTS
    }

    /**
     * 一档。
     *
     * <p>{@code baseline == null} ⟺ {@code status != null}——**不留裸 null，也不用 0 顶缺**。
     * 前端据此把那格渲染成 {@code status} 的文案，标签照留，不丢档。
     */
    public record Cell(String label, LocalDate baselineDate, BigDecimal baseline,
                       BigDecimal change, String status) {
        public boolean present() {
            return baseline != null;
        }
    }

    /**
     * 一行。{@code current} 与 {@code currentDate} 取的是序列**最后一点**，
     * 不是「服务器今天」——数据陈旧时，基线必须相对数据自身的日期算，
     * 否则「昨」会变成「三天前」，而页面上写着昨天。
     */
    public record Result(BigDecimal current, LocalDate currentDate, List<Cell> cells) {}

    private LookbackCalculator() {}

    /**
     * 算八档。
     *
     * @param series           观测序列，任意顺序、可含 null 与坏数据，内部会清洗排序
     * @param style            变化的算法
     * @param shortHistoryNote 历史不够长时替换用的说明（按来源分别写，用**实际根数与最早日期**拼，
     *                         例如「日线取自腾讯（兜底），本次只有 800 根（最早 2023-06-15），
     *                         超出这个跨度的档位未确认」）。
     *                         <b>只作用于「历史不足」这一种缺档</b>——「当日附近没有观测」
     *                         另有原因，套用来源那句话说会指错方向。为 null 时用通用文案。
     */
    public static Result compute(List<Point> series, ChangeStyle style, String shortHistoryNote) {
        List<Point> sorted = clean(series, style);
        if (sorted.isEmpty()) {
            // **来源说明对空序列同样适用**，而且这里最需要用：序列一个点都没有的真相，
            // 往往是「这个源根本不提供历史」（蛋卷只给当日值），而不是「没取到」。
            // 用通用文案会把前者说成后者——一个只能换源，一个重试就行。
            return new Result(null, null, missingAll(
                    shortHistoryNote != null && !shortHistoryNote.isBlank()
                            ? shortHistoryNote
                            : "未取到任何有效观测，八档基线都未确认"));
        }

        Point last = sorted.get(sorted.size() - 1);
        LocalDate asOf = last.date();
        BigDecimal current = last.value();
        LocalDate oldest = sorted.get(0).date();

        List<Cell> cells = new ArrayList<>(LABELS.size());
        for (String label : LABELS) {
            LocalDate target = targetDate(asOf, label);
            Point base = latestOnOrBefore(sorted, target, STALE_DAYS);
            if (base == null) {
                cells.add(new Cell(label, null, null, null,
                        missingReason(label, target, oldest, asOf, shortHistoryNote)));
                continue;
            }
            BigDecimal change = change(style, current, base.value());
            if (change == null) {
                // 兜底，正常到不了这里：PRICE 口径下 clean 已经把非正的收盘价筛掉了，
                // POINTS 口径的 change 从不返回 null。留着是为了万一以后有人动了
                // clean 的过滤条件——那时这里会给出原因，而不是一个 null change
                // 配一个 null status（前端会渲染成空白格，比报错难查得多）。
                cells.add(new Cell(label, base.date(), base.value(), null,
                        "基线价格非正（" + base.date() + "），涨跌幅未确认"));
                continue;
            }
            cells.add(new Cell(label, base.date(), base.value(), change, null));
        }
        return new Result(current, asOf, cells);
    }

    /**
     * 某一档的目标日。{@code 昨}/{@code 周} 用自然日，{@code 月}/{@code 半年} 用日历月，
     * 更长的一律用日历年——与 {@code etf_report.build_pe_context} 逐个对应。
     */
    static LocalDate targetDate(LocalDate asOf, String label) {
        return switch (label) {
            case "昨" -> asOf.minusDays(1);
            case "周" -> asOf.minusDays(7);
            case "月" -> asOf.minusMonths(1);
            case "半年" -> asOf.minusMonths(6);
            case "一年" -> asOf.minusYears(1);
            case "三年" -> asOf.minusYears(3);
            case "五年" -> asOf.minusYears(5);
            case "十年" -> asOf.minusYears(10);
            default -> throw new IllegalArgumentException("未知档位 " + label);
        };
    }

    /**
     * 在 {@code target} 当天或之前、且距它不超过 {@code staleDays} 天的最近一次观测。
     *
     * <p>先按日期定位到「不晚于 target 的最后一点」，再看它是否过旧。过旧就返回 null，
     * <b>不再往前翻</b>——更早的那些只会更旧，往前翻等于把一个三个月前的价当成「昨」。
     */
    static Point latestOnOrBefore(List<Point> ascending, LocalDate target, int staleDays) {
        for (int i = ascending.size() - 1; i >= 0; i--) {
            Point p = ascending.get(i);
            if (p.date().isAfter(target)) {
                continue;
            }
            return ChronoUnit.DAYS.between(p.date(), target) > staleDays ? null : p;
        }
        return null;
    }

    /** 价格用相对涨幅、分位用百分点差。基线非正时返回 null（不可比，不编数）。 */
    static BigDecimal change(ChangeStyle style, BigDecimal current, BigDecimal baseline) {
        if (style == ChangeStyle.POINTS) {
            return current.subtract(baseline).setScale(2, RoundingMode.HALF_UP);
        }
        if (baseline.signum() <= 0) {
            return null;
        }
        return current.subtract(baseline).multiply(BigDecimal.valueOf(100), MC)
                .divide(baseline, MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 洗掉坏数据并升序。价格序列额外要求收盘价为正——负价与 0 都是坏数据。 */
    static List<Point> clean(List<Point> series, ChangeStyle style) {
        if (series == null || series.isEmpty()) {
            return List.of();
        }
        return series.stream()
                .filter(p -> p != null && p.date() != null && p.value() != null)
                .filter(p -> style != ChangeStyle.PRICE || p.value().signum() > 0)
                .sorted(Comparator.comparing(Point::date))
                .toList();
    }

    /**
     * 缺档的原因。<b>三种原因必须分开写</b>，因为下一步该做什么完全不同：
     * 「来源不提供」只能换源，「历史不足」要等历史攒够，「当日附近没有观测」要去查停牌。
     */
    static String missingReason(String label, LocalDate target, LocalDate oldest, LocalDate asOf,
                                String shortHistoryNote) {
        if (oldest.isAfter(target)) {
            if (shortHistoryNote != null && !shortHistoryNote.isBlank()) {
                return shortHistoryNote;
            }
            double years = ChronoUnit.DAYS.between(oldest, asOf) / 365.25;
            return String.format("历史不足：观测自 %s 起（约 %.1f 年），取不到「%s」的基线",
                    oldest, years, label);
        }
        return String.format("「%s」当日（%s）前后 %d 天内没有观测，基线未确认",
                label, target, STALE_DAYS);
    }

    private static List<Cell> missingAll(String status) {
        List<Cell> cells = new ArrayList<>(LABELS.size());
        for (String label : LABELS) {
            cells.add(new Cell(label, null, null, null, status));
        }
        return cells;
    }
}