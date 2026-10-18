package com.ai.daily.screener.v2;

import com.ai.daily.screener.RollingPercentile;
import com.ai.daily.warehouse.WarehouseRows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个股估值序列上的分位计算。
 *
 * <p><b>分位必须与 PE 同源</b>：这里只接受 {@code md_valuation_daily} 里
 * {@code source=eastmoney-valueanalysis} 的点。行情接口的当日 f115 是另一份快照，
 * 拿它和这条序列比，会得到「同一只票的两个 PE」——旧页面踩过，别再来一次。
 *
 * <p>算法直接用 {@link RollingPercentile}（与中证/蛋卷那套逐点对齐），
 * **不另写一份近似的分位**：两份算法差 0.3 个百分点，没有任何页面能看出来，
 * 但用户会觉得「怎么和上次不一样」。
 */
public final class ValuationMath {

    private ValuationMath() {
    }

    /**
     * 一次算完 PE 与 PB 的逐日滚动分位。
     *
     * @param pePoints 有效 PE 点数（&gt; 0）
     * @param pbPoints 有效 PB 点数（&gt; 0）
     * @param windowFrom 整段序列的第一个日期
     */
    public record Percentiles(Map<LocalDate, BigDecimal> pe, Map<LocalDate, BigDecimal> pb,
                       int pePoints, int pbPoints, LocalDate windowFrom) {
        static final Percentiles EMPTY = new Percentiles(Map.of(), Map.of(), 0, 0, null);
    }

    public static Percentiles compute(List<WarehouseRows.ValuationPoint> points) {
        if (points == null || points.isEmpty()) {
            return Percentiles.EMPTY;
        }
        List<RollingPercentile.Point> pe = new ArrayList<>();
        List<RollingPercentile.Point> pb = new ArrayList<>();
        for (WarehouseRows.ValuationPoint p : points) {
            if (p.tradeDate() == null) continue;
            if (p.peTtm() != null && p.peTtm().signum() > 0) {
                pe.add(new RollingPercentile.Point(p.tradeDate(), p.peTtm()));
            }
            if (p.pbMrq() != null && p.pbMrq().signum() > 0) {
                pb.add(new RollingPercentile.Point(p.tradeDate(), p.pbMrq()));
            }
        }
        return new Percentiles(index(pe, RollingPercentile.rolling(pe)),
                index(pb, RollingPercentile.rolling(pb)),
                pe.size(), pb.size(), points.get(0).tradeDate());
    }

    private static Map<LocalDate, BigDecimal> index(List<RollingPercentile.Point> points,
                                                    List<BigDecimal> values) {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        for (int i = 0; i < points.size(); i++) {
            out.put(points.get(i).date(), values.get(i));
        }
        return out;
    }

    /** 序列最后一天的 PE 分位；没有有效点时为 null。 */
    public static BigDecimal latestPePercentile(Percentiles percentiles, LocalDate date) {
        return date == null ? null : percentiles.pe().get(date);
    }
}
