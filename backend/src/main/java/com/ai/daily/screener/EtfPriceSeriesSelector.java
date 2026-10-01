package com.ai.daily.screener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从**混着多个来源**的本地日线里挑出**一条同源序列**。
 *
 * <p><b>为什么必须有这一步</b>：{@code etf_price_history} 的唯一键是
 * {@code (fund_code, trade_date, adjustment_type, source)}——**source 在键里面**。
 * 于是同一只 ETF 的同一天可以同时躺着「东财行」和「腾讯行」，而按日期排出来的序列
 * 会在中间某处**静默换源**。换源之后那 250 根曲线前段和后段来自两家公司的复权基准，
 * 算出来的价格分位、回撤、年化波动全是错的，**而且不会报错**：每一根都是「合法」的日线。
 * 所以读库之后的第一件事，永远是先按 source 分组、只留一组。
 *
 * <p>判定照抄 Python 侧已跑通的 {@code automation/scripts/etf_report.py select_cached_price_series}
 * （:548）——同一件事在两个语言里各写一套判据，等于埋了两套 bug：
 * <ol>
 *   <li>按 source 分组，各自排序去重、剔掉坏行；</li>
 *   <li>取「最后一根日期最新」的那批；</li>
 *   <li>其中「尾部连续段最长」的胜出，再平手则总根数多的胜出。</li>
 * </ol>
 * 「尾部连续段」= 从最后一根往前数，相邻两根相隔 1–4 个自然日的那些（4 天覆盖周末与单日假期）。
 * 这条判据是用来识别**断更**的：某源上次同步失败、只剩半截序列时，它的尾巴会短，
 * 哪怕它最后一根的日期看起来更新。
 *
 * <p>无网络、无 Spring、无数据库依赖，可直接单测，见 {@code EtfBarFromDbTest}。
 */
public final class EtfPriceSeriesSelector {

    /** 只有前复权能算价格位置。混进不复权的行会在除权日显示假跌，把正常分红判成「便宜」。 */
    public static final String QFQ = "QFQ";

    /** 尾部连续段的相邻两根最多允许隔这么多自然日（覆盖周末 + 单日假期）。 */
    private static final int MAX_GAP_DAYS = 4;

    private EtfPriceSeriesSelector() {}

    /**
     * 一条待选的日线。只需要日期、收盘价与来源——判定不碰开高低量，
     * 因为它们不参与价格位置的任何计算。
     *
     * <p>{@code adjustmentType} 也带进来是**故意的**：SQL 已经筛过一次，
     * 但混口径的后果是静默的（序列照样算得出数，只是数错了），
     * 所以这里再挡一次，而不是信任调用方一定没漏。
     */
    public record Point(LocalDate date, BigDecimal close, String source, String adjustmentType) {
        public static Point of(LocalDate date, BigDecimal close, String source) {
            return new Point(date, close, source, QFQ);
        }
    }

    /**
     * 挑出同源序列，并只保留最后 {@code limit} 根。
     *
     * @param points 混源的日线，顺序无所谓
     * @param limit  最多保留多少根（价格位置的窗口是 250 个交易日）
     * @return 升序的同源日线；一组都挑不出来时返回空列表——**空列表就是「没有」，
     *         不允许拿任何默认值糊上**
     */
    public static List<PricePositionCalculator.Bar> select(List<Point> points, int limit) {
        if (points == null || points.isEmpty()) return List.of();

        // 分组用 LinkedHashMap：平手时要取「先出现的那个」，插入顺序必须稳定，
        // 否则同一份数据两次调用可能给出不同结果。
        Map<String, Map<LocalDate, Point>> bySource = new LinkedHashMap<>();
        for (Point p : points) {
            if (!isUsable(p)) continue;
            bySource.computeIfAbsent(p.source().trim(), k -> new LinkedHashMap<>())
                    .put(p.date(), p);
        }

        Candidate best = null;
        for (Map.Entry<String, Map<LocalDate, Point>> group : bySource.entrySet()) {
            List<Point> series = new ArrayList<>(group.getValue().values());
            series.sort(Comparator.comparing(Point::date));
            if (series.isEmpty()) continue;

            Candidate c = new Candidate(series.get(series.size() - 1).date(),
                    trailingRun(series), series.size(), group.getKey(), series);
            if (best == null || c.beats(best)) best = c;
        }
        if (best == null) return List.of();

        List<Point> chosen = best.series();
        int from = Math.max(0, chosen.size() - Math.max(1, limit));
        List<PricePositionCalculator.Bar> bars = new ArrayList<>(chosen.size() - from);
        for (Point p : chosen.subList(from, chosen.size())) {
            bars.add(new PricePositionCalculator.Bar(p.date(), p.close()));
        }
        return bars;
    }

    /** 坏行不是「便宜」，是坏行。留着会把价格分位压到 0 并伪造一个巨大回撤。 */
    private static boolean isUsable(Point p) {
        if (p == null || p.date() == null || p.close() == null || p.close().signum() <= 0) return false;
        if (p.source() == null || p.source().isBlank()) return false;
        return p.adjustmentType() == null || QFQ.equalsIgnoreCase(p.adjustmentType().trim());
    }

    /** 从最后一根往前数，相邻两根相隔 1–{@value #MAX_GAP_DAYS} 天的连续段长度。非空序列至少为 1。 */
    static int trailingRun(List<Point> ascending) {
        int run = 1;
        for (int i = ascending.size() - 1; i > 0; i--) {
            long gap = ChronoUnit.DAYS.between(ascending.get(i - 1).date(), ascending.get(i).date());
            if (gap >= 1 && gap <= MAX_GAP_DAYS) run++;
            else break;
        }
        return run;
    }

    private record Candidate(LocalDate lastDate, int run, int size, String source,
                             List<Point> series) {
        /** 先比最后一根的日期，再比尾部连续段，最后比总根数。严格大于才算赢——平手取先出现的。 */
        boolean beats(Candidate other) {
            int byDate = lastDate.compareTo(other.lastDate);
            if (byDate != 0) return byDate > 0;
            if (run != other.run) return run > other.run;
            return size > other.size;
        }
    }
}