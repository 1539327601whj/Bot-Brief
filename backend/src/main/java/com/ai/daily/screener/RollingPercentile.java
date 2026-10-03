package com.ai.daily.screener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 滚动窗口分位。<b>与 {@code automation/scripts/etf_report.py} 的
 * {@code fetch_csindex_pe_history} 是同一个算法，必须逐点对得上。</b>
 *
 * <p>「同一个分位两个算法」这种事没有任何页面能看出来：两边差 0.3 个百分点，
 * 用户只会觉得「好像和上次不太一样」。所以 {@code RollingPercentileTest} 里有一份
 * 由 Python 生成的对照数据（{@code automation/tests/fixtures/pe_percentile_parity.json}），
 * 两边读同一份、比同一串数字。**改这里的算法就要同步改那边，并且重跑那个测试。**
 *
 * <p>算法（照抄 Python，逐句对应）：
 * <ol>
 *   <li>按交易日升序逐点推进；</li>
 *   <li>窗口的左端是 {@code subtract_years(当日, 10)}，**按日期**而不是按条数——
 *       「最近 2500 个交易日」与「最近 10 年」在停牌、长假之后不是一回事；</li>
 *   <li>窗口里放的是**所有落在区间内的值**（含当日自己，含重复值）；</li>
 *   <li>分位 = 窗口内 {@code ≤ 当日值} 的个数 ÷ 窗口大小 × 100
 *       （Python 用的是 {@code bisect_right}/len，也就是「小于等于」）。</li>
 * </ol>
 *
 * <p>因此：当日值是窗口里最大的一批时得 100，最小的一批时得 {@code 100/窗口大小}。
 * <b>永远得不到 0</b>——所以看到 0 就意味着「没有数据」，而不是「极度低估」。
 *
 * <p>窗口天然是 {@code min(10 年, 该序列实际可用的长度)}：历史不够 10 年时，
 * 左端根本取不到更早的点。这也意味着**科创50 这类只有约 6 年历史的指数，
 * 它的「10 年分位」实际是 6 年窗口的**——方法名里写着 10Y，页面必须另外标出
 * 实际历史长度（章程 §2.4）。
 *
 * <p>无网络、无 Spring 依赖，可直接单测。
 */
public final class RollingPercentile {

    /** 章程里那个窗口年数。与 Python 的 {@code CSI300_PE_WINDOW_YEARS} 是同一个数。 */
    public static final int WINDOW_YEARS = 10;

    /** 库里 {@code pe_percentile} 是 DECIMAL(8,4)，两边都按 4 位存，比较才有意义。 */
    private static final int SCALE = 4;

    /** 一个观测点：交易日 + 当天的 PE(TTM)。 */
    public record Point(LocalDate date, BigDecimal value) {}

    private RollingPercentile() {}

    /**
     * 对升序序列逐点算滚动分位，返回与入参**等长、同序**的分位列表（0–100，4 位小数）。
     *
     * <p>入参里的坏数据（日期或值为空、值非正）会被丢掉，所以返回的列表可能比入参短——
     * 调用方若要按位置对齐，请先用 {@link #clean} 得到清洗后的序列。
     */
    public static List<BigDecimal> rolling(List<Point> points, int windowYears) {
        List<Point> sorted = clean(points);
        List<BigDecimal> out = new ArrayList<>(sorted.size());
        if (sorted.isEmpty()) {
            return out;
        }

        // 窗口的多重集合。TreeMap 而不是 ArrayList：窗口左端要**按值**删掉一个旧点，
        // 用列表每次都是 O(n) 搬运，2560 个点会变成几百万次拷贝。
        TreeMap<BigDecimal, Integer> window = new TreeMap<>();
        int windowSize = 0;
        int left = 0;

        for (Point p : sorted) {
            LocalDate minimum = p.date().minusYears(windowYears);
            // 左端只前进不回退，均摊 O(n)：这些点一旦出窗就再也不会进来
            while (left < sorted.size() && sorted.get(left).date().isBefore(minimum)) {
                BigDecimal expired = sorted.get(left).value();
                int count = window.getOrDefault(expired, 0);
                if (count <= 1) {
                    window.remove(expired);
                } else {
                    window.put(expired, count - 1);
                }
                windowSize--;
                left++;
            }

            window.merge(p.value(), 1, Integer::sum);
            windowSize++;

            out.add(percentileAtOrBelow(window, p.value(), windowSize));
        }
        return out;
    }

    /** 只算最后一个点的分位（点击时最常用）。序列为空或全是坏数据时返回 empty。 */
    public static Optional<BigDecimal> latest(List<Point> points, int windowYears) {
        List<BigDecimal> all = rolling(points, windowYears);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /** 默认窗口（10 年）。 */
    public static List<BigDecimal> rolling(List<Point> points) {
        return rolling(points, WINDOW_YEARS);
    }

    /** 默认窗口（10 年）下的最新分位。 */
    public static Optional<BigDecimal> latest(List<Point> points) {
        return latest(points, WINDOW_YEARS);
    }

    /**
     * 窗口内 {@code ≤ value} 的个数 ÷ 窗口大小 × 100。
     *
     * <p>这里的 {@code headMap(value, true)} 与 Python 的 {@code bisect_right} 是同一个语义：
     * **含等于**。写成严格小于会让每一个重复值都掉一档，而重复值在日频 PE 里很常见
     * （估值没变的日子）。
     *
     * <p>复杂度：每次要遍历窗口里小于等于该值的那些键。窗口 ≤ 约 2560 个点，
     * 且只在按需点击时算一次，不构成问题；真要提到 O(log n) 得换 Fenwick 树，
     * 那属于「以后真觉得慢再说」。
     */
    private static BigDecimal percentileAtOrBelow(TreeMap<BigDecimal, Integer> window,
                                                  BigDecimal value, int windowSize) {
        int atOrBelow = 0;
        for (Map.Entry<BigDecimal, Integer> e : window.headMap(value, true).entrySet()) {
            atOrBelow += e.getValue();
        }
        return BigDecimal.valueOf(atOrBelow)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(windowSize), SCALE, RoundingMode.HALF_UP);
    }

    /** 丢掉坏数据并升序。非正 PE 直接丢——它算进分位会把整条曲线拉低。 */
    static List<Point> clean(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        return points.stream()
                .filter(p -> p != null && p.date() != null && p.value() != null)
                .filter(p -> p.value().signum() > 0)
                .sorted(java.util.Comparator.comparing(Point::date))
                .toList();
    }
}