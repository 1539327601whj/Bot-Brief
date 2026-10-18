package com.ai.daily.screener.v2;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.service.MarketValuationHistoryService;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.InstrumentResolver;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图表端点：价格 / 估值 / 资金流序列。
 *
 * <p><b>只读本地库</b>，一次外呼都不发。图表是用户拖来拖去的，跟着它去敲源等于把
 * 限流额度交给鼠标。
 *
 * <p><b>降采样规则</b>：
 * <ul>
 *   <li>请求 {@code 1d} 但跨度 10 年 → 自动给周线，并在 {@code interval} 里如实回报；</li>
 *   <li>点数超过 1800 根 → 同样转周线。响应体控制在 150KB 以内，慢网络下也拖得动。</li>
 * </ul>
 *
 * <p>周线聚合不是「每五根取一根」：开高低收各自取首/最大/最小/末，量额求和。
 * 直接抽最后一根会让周线的最高价低于日内高点，支撑位算出来是错的。
 */
@Service
@RequiredArgsConstructor
public class SeriesService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAILY_POINTS = 1800;

    private final InstrumentResolver resolver;
    private final WarehouseRepository repository;
    private final MarketValuationHistoryService valuationHistoryService;
    private final IndexFundPool indexFundPool;
    private final WarehouseSchemaGuard schemaGuard;
    private final WarehouseProperties properties;

    public CodeLookupV2DTO.SeriesView series(String rawCode, String kind, String range,
                                             String interval) {
        // 与 core/detail 同一档错误：底座没就绪时回 503，不要让它变成一条 SQL 异常。
        if (!properties.isEnabled()) {
            throw new WarehouseUnavailableException(
                    "代码查询 v2 已关闭（warehouse.enabled=false），请使用旧版页面");
        }
        if (!schemaGuard.isReady()) {
            throw new WarehouseUnavailableException(schemaGuard.unavailableReason());
        }
        InstrumentCode instrument = resolver.resolve(rawCode);
        String safeKind = kind == null || kind.isBlank() ? "price" : kind.strip().toLowerCase();
        String safeRange = normaliseRange(range);
        LocalDate today = LocalDate.now(SHANGHAI);
        LocalDate from = today.minusYears(years(safeRange));

        return switch (safeKind) {
            case "valuation" -> valuationSeries(instrument, safeRange, interval, from, today);
            case "moneyflow" -> moneyflowSeries(instrument, safeRange, interval, from, today);
            case "price" -> priceSeries(instrument, safeRange, interval, from, today);
            default -> throw new IllegalArgumentException(
                    "kind 只能是 price / valuation / moneyflow，实际=" + kind);
        };
    }

    // ------------------------------------------------------------------
    // 价格
    // ------------------------------------------------------------------

    private CodeLookupV2DTO.SeriesView priceSeries(InstrumentCode instrument, String range,
                                                    String interval, LocalDate from,
                                                    LocalDate today) {
        List<WarehouseRows.PriceBar> rows = repository.pricesBetween(
                instrument.key(), OnDemandSyncService.ADJ_QFQ, from, today);
        List<CodeLookupV2DTO.PricePoint> points = new ArrayList<>(rows.size());
        for (WarehouseRows.PriceBar row : rows) {
            points.add(new CodeLookupV2DTO.PricePoint(
                    row.tradeDate().toString(), row.openPrice(), row.highPrice(), row.lowPrice(),
                    row.closePrice(), row.volume(), row.amount(), row.pctChange(),
                    row.turnoverRate()));
        }

        String actual = resolveInterval(interval, range, rows.size());
        if ("1w".equals(actual)) {
            points = weeklyPrices(points);
        }
        String note = rows.isEmpty()
                ? "本地还没有这只标的的日线（首次查询会同步，或由 warehouse 盘后回填）"
                : null;
        return new CodeLookupV2DTO.SeriesView(instrument.key(), "price", range, actual,
                points, List.of(), List.of(), note);
    }

    /** 周线：按 ISO 周（周一起）聚合。输入必须是按日期升序的日线。 */
    static List<CodeLookupV2DTO.PricePoint> weeklyPrices(List<CodeLookupV2DTO.PricePoint> daily) {
        Map<LocalDate, List<CodeLookupV2DTO.PricePoint>> weeks = new LinkedHashMap<>();
        for (CodeLookupV2DTO.PricePoint point : daily) {
            LocalDate date = LocalDate.parse(point.date());
            LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeks.computeIfAbsent(monday, k -> new ArrayList<>()).add(point);
        }
        List<CodeLookupV2DTO.PricePoint> out = new ArrayList<>(weeks.size());
        for (Map.Entry<LocalDate, List<CodeLookupV2DTO.PricePoint>> e : weeks.entrySet()) {
            List<CodeLookupV2DTO.PricePoint> week = e.getValue();
            CodeLookupV2DTO.PricePoint first = week.get(0);
            CodeLookupV2DTO.PricePoint last = week.get(week.size() - 1);
            BigDecimal high = null;
            BigDecimal low = null;
            BigDecimal volume = null;
            BigDecimal amount = null;
            for (CodeLookupV2DTO.PricePoint p : week) {
                high = max(high, p.high());
                low = min(low, p.low());
                volume = sum(volume, p.volume());
                amount = sum(amount, p.amount());
            }
            out.add(new CodeLookupV2DTO.PricePoint(last.date(), first.open(),
                    high != null ? high : last.close(),
                    low != null ? low : last.close(),
                    last.close(), volume, amount, last.pctChange(), last.turnoverRate()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 估值
    // ------------------------------------------------------------------

    private CodeLookupV2DTO.SeriesView valuationSeries(InstrumentCode instrument, String range,
                                                       String interval, LocalDate from,
                                                       LocalDate today) {
        if (instrument.isStock()) {
            // 分位必须在**整段历史**上滚动，再截到显示区间；只拿区间内的点算会系统性偏高/偏低。
            List<WarehouseRows.ValuationPoint> all = repository.valuationSeries(
                    instrument.key(), OnDemandSyncService.VALUATION_SOURCE, today.minusYears(11));
            ValuationMath.Percentiles percentiles = ValuationMath.compute(all);

            List<CodeLookupV2DTO.ValuationPoint> points = new ArrayList<>();
            for (WarehouseRows.ValuationPoint p : all) {
                if (p.tradeDate().isBefore(from)) continue;
                points.add(new CodeLookupV2DTO.ValuationPoint(p.tradeDate().toString(),
                        p.peTtm(), p.pbMrq(), p.dividendYield(), percentiles.pe().get(p.tradeDate())));
            }
            String note = points.isEmpty()
                    ? "本地还没有这只票的估值历史（东财估值分析源覆盖 2018 年起）"
                    : null;
            return new CodeLookupV2DTO.SeriesView(instrument.key(), "valuation", range,
                    "1d", List.of(), points, List.of(), note);
        }

        String indexCode = instrument.isIndex() ? instrument.rawCode()
                : trackingIndexOf(instrument);
        IndexFundPool.Fund fund = indexCode == null ? null : indexFundPool.byIndexCode(indexCode);
        if (fund == null || fund.percentileMethod() == null) {
            return new CodeLookupV2DTO.SeriesView(instrument.key(), "valuation", range, "1d",
                    List.of(), List.of(), List.of(),
                    "指数池没有为这只标的配置 PE 分位来源，免费源未覆盖");
        }
        List<MarketValuationHistory> rows = valuationHistoryService.historyBetween(
                indexCode, fund.percentileMethod(), from, today);
        List<CodeLookupV2DTO.ValuationPoint> points = new ArrayList<>(rows.size());
        for (MarketValuationHistory row : rows) {
            points.add(new CodeLookupV2DTO.ValuationPoint(row.getTradeDate().toString(),
                    row.getPeTtm(), row.getPb(), row.getDividendYield(), row.getPePercentile()));
        }
        String note = points.isEmpty() ? "估值库里还没有 " + indexCode + " 的历史" : null;
        return new CodeLookupV2DTO.SeriesView(instrument.key(), "valuation", range, "1d",
                List.of(), points, List.of(), note);
    }

    // ------------------------------------------------------------------
    // 资金流
    // ------------------------------------------------------------------

    private CodeLookupV2DTO.SeriesView moneyflowSeries(InstrumentCode instrument, String range,
                                                       String interval, LocalDate from,
                                                       LocalDate today) {
        if (!instrument.isStock()) {
            return new CodeLookupV2DTO.SeriesView(instrument.key(), "moneyflow", range, "1d",
                    List.of(), List.of(), List.of(), "ETF/指数不提供个股资金流");
        }
        List<WarehouseRows.MoneyFlow> rows = repository.moneyflowBetween(
                instrument.rawCode(), from, today);
        List<CodeLookupV2DTO.MoneyFlowPoint> points = new ArrayList<>(rows.size());
        for (WarehouseRows.MoneyFlow row : rows) {
            points.add(new CodeLookupV2DTO.MoneyFlowPoint(row.tradeDate().toString(),
                    row.mainNet(), row.superNet(), row.largeNet(), row.mediumNet(),
                    row.smallNet()));
        }
        String actual = resolveInterval(interval, range, points.size());
        if ("1w".equals(actual)) {
            points = weeklyFlows(points);
        }
        String note = points.isEmpty() ? "本地还没有这只票的资金流（由 warehouse 按需同步）" : null;
        return new CodeLookupV2DTO.SeriesView(instrument.key(), "moneyflow", range, actual,
                List.of(), List.of(), points, note);
    }

    static List<CodeLookupV2DTO.MoneyFlowPoint> weeklyFlows(
            List<CodeLookupV2DTO.MoneyFlowPoint> daily) {
        Map<LocalDate, List<CodeLookupV2DTO.MoneyFlowPoint>> weeks = new LinkedHashMap<>();
        for (CodeLookupV2DTO.MoneyFlowPoint p : daily) {
            LocalDate monday = LocalDate.parse(p.date())
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeks.computeIfAbsent(monday, k -> new ArrayList<>()).add(p);
        }
        List<CodeLookupV2DTO.MoneyFlowPoint> out = new ArrayList<>(weeks.size());
        for (List<CodeLookupV2DTO.MoneyFlowPoint> week : weeks.values()) {
            CodeLookupV2DTO.MoneyFlowPoint last = week.get(week.size() - 1);
            BigDecimal main = null, superNet = null, large = null, medium = null, small = null;
            for (CodeLookupV2DTO.MoneyFlowPoint p : week) {
                main = sum(main, p.mainNet());
                superNet = sum(superNet, p.superNet());
                large = sum(large, p.largeNet());
                medium = sum(medium, p.mediumNet());
                small = sum(small, p.smallNet());
            }
            out.add(new CodeLookupV2DTO.MoneyFlowPoint(last.date(), main, superNet, large,
                    medium, small));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private String trackingIndexOf(InstrumentCode instrument) {
        IndexFundPool.Fund fund = indexFundPool.byEtfCode(instrument.rawCode());
        return fund == null ? null : fund.indexCode();
    }

    static String normaliseRange(String range) {
        if (range == null) return "1y";
        String value = range.strip().toLowerCase();
        return switch (value) {
            case "1y", "3y", "5y", "10y" -> value;
            default -> throw new IllegalArgumentException(
                    "range 只能是 1y / 3y / 5y / 10y，实际=" + range);
        };
    }

    static int years(String range) {
        return switch (range) {
            case "3y" -> 3;
            case "5y" -> 5;
            case "10y" -> 10;
            default -> 1;
        };
    }

    /** 返回实际采用的粒度。请求 1d 时只有过长才降为 1w。 */
    static String resolveInterval(String interval, String range, int dailyPoints) {
        String requested = interval == null || interval.isBlank()
                ? "1d" : interval.strip().toLowerCase();
        if (!"1d".equals(requested) && !"1w".equals(requested)) {
            throw new IllegalArgumentException("interval 只能是 1d / 1w，实际=" + interval);
        }
        if ("1w".equals(requested)) {
            return "1w";
        }
        if ("10y".equals(range) || dailyPoints > MAX_DAILY_POINTS) {
            return "1w";
        }
        return "1d";
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }
}
