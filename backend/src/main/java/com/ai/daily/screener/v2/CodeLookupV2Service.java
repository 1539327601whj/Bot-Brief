package com.ai.daily.screener.v2;

import com.ai.daily.screener.LookbackCalculator;
import com.ai.daily.screener.v2.analysis.AnalysisResult;
import com.ai.daily.screener.v2.analysis.BuyZone;
import com.ai.daily.screener.v2.analysis.DimensionScore;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.screener.v2.analysis.Reason;
import com.ai.daily.screener.v2.analysis.Scenario;
import com.ai.daily.screener.v2.analysis.ScoreMetric;
import com.ai.daily.screener.v2.analysis.Verdict;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码查询 v2 的读模型装配。
 *
 * <p><b>这一层不取数、不算分</b>：取数在 {@link OnDemandSyncService}，
 * 事实装配在 {@link InstrumentFactsService}，打分在 P4。这里只做两件事：
 * 把 {@link FactsSnapshot} 翻成页面能直接渲染的结构，以及把「缺什么、为什么缺」
 * 原样带到每一段上。
 *
 * <p><b>为什么每个端点都重新拿一次快照</b>：快照本身命中本地库时只查几次索引
 * （行情缓存 60 秒、日线/估值按新鲜度判断），代价远小于在进程里再维护一份
 * 「一半旧一半新」的缓存。价格、估值、财务任一数据日变化，{@code snapshotId}
 * 就会变，detail 用 {@code snapshotChanged} 通知前端重拉 core。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeLookupV2Service {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int SEARCH_LIMIT = 20;
    /** 与旧页 {@code LookbackCalculator.STALE_DAYS} 同一个数：停牌太久不能假装「昨」。 */
    private static final int STALE_DAYS = LookbackCalculator.STALE_DAYS;

    private final InstrumentFactsService factsService;
    private final InvestmentAnalysisService analysisService;
    private final WarehouseRepository repository;
    private final WarehouseSchemaGuard schemaGuard;
    private final WarehouseProperties properties;

    // ------------------------------------------------------------------
    // core
    // ------------------------------------------------------------------

    public CodeLookupV2DTO.CoreView core(String rawCode) {
        requireReady();
        FactsSnapshot snapshot = factsService.snapshot(rawCode);

        CodeLookupV2DTO.QuoteView quote = quoteView(snapshot.quote());
        CodeLookupV2DTO.ValuationView valuation = valuationView(snapshot);
        CodeLookupV2DTO.TechnicalView technical =
                technicalView(TechnicalCalculator.compute(snapshot.prices().data()));
        AnalysisResult analysis = analysisService.analyze(snapshot);

        Map<String, CodeLookupV2DTO.DomainView> data = domainMap(snapshot);
        List<String> notes = notes(snapshot);

        return new CodeLookupV2DTO.CoreView(
                snapshot.inputCode(),
                snapshot.instrument().rawCode(),
                snapshot.identity().name(),
                snapshot.instrument().assetType(),
                snapshot.instrument().market(),
                snapshot.identity().industry(),
                snapshot.identity().sectorProfile(),
                snapshot.identity().trackingIndexCode(),
                snapshot.snapshotId(),
                snapshot.snapshotAt(),
                snapshot.fromCache(),
                snapshot.syncPending(),
                snapshot.retryAfterMs(),
                quote,
                valuation,
                technical,
                verdictView(analysis.verdict()),
                dimensionViews(analysis.score().dimensions()),
                buyZoneView(analysis.buyZone()),
                priceLookbacks(snapshot),
                peLookbacks(snapshot),
                data,
                notes,
                analysis.rulesVersion(),
                CodeLookupV2DTO.DISCLAIMER);
    }

    private static CodeLookupV2DTO.QuoteView quoteView(FactsSnapshot.Domain<FactsSnapshot.Quote> domain) {
        FactsSnapshot.Quote q = domain.data();
        if (q == null) {
            return new CodeLookupV2DTO.QuoteView(null, null, null, null, null, null,
                    null, null, null, null, null);
        }
        return new CodeLookupV2DTO.QuoteView(q.provider(), q.providerLabel(), q.price(),
                q.pctChange(), q.amount(), q.turnoverRate(), q.totalMarketCap(), q.peTtm(),
                q.pb(), q.dividendYield(), q.asOf() == null ? null : q.asOf().toString());
    }

    /**
     * 个股与 ETF/指数的估值走两条路，绝不互相顶替。
     *
     * <p>个股的分位来自东财估值分析整段历史（{@code md_valuation_daily}），
     * ETF/指数的分位来自中证/蛋卷（{@code market_valuation_history}）。
     * 把当日行情的 PE 拿来拼历史，或者把指数的 PE 塞进个股那格，
     * 都会得到两个看起来都像真的、但互相对不上的数字。
     */
    private static CodeLookupV2DTO.ValuationView valuationView(FactsSnapshot snapshot) {
        if (snapshot.instrument().isStock()) {
            FactsSnapshot.Domain<List<WarehouseRows.ValuationPoint>> domain = snapshot.valuations();
            List<WarehouseRows.ValuationPoint> points = domain.data();
            if (points == null || points.isEmpty()) {
                return new CodeLookupV2DTO.ValuationView(null, null, null, null, null, null,
                        0, null, null, null, 0, domain.asOf(), domain.note());
            }
            WarehouseRows.ValuationPoint latest = latestUsable(points);
            ValuationMath.Percentiles percentiles = ValuationMath.compute(points);
            LocalDate date = latest == null ? null : latest.tradeDate();
            BigDecimal pe = latest == null ? null : latest.peTtm();
            BigDecimal pb = latest == null ? null : latest.pbMrq();
            return new CodeLookupV2DTO.ValuationView(
                    OnDemandSyncService.VALUATION_SOURCE,
                    "东财估值分析",
                    null,
                    positive(pe),
                    ValuationMath.latestPePercentile(percentiles, date),
                    percentiles.windowFrom() == null ? null : percentiles.windowFrom().toString(),
                    percentiles.pePoints(),
                    positive(pb),
                    date == null ? null : percentiles.pb().get(date),
                    latest == null ? null : latest.dividendYield(),
                    points.size(),
                    date == null ? domain.asOf() : date.toString(),
                    domain.note());
        }

        FactsSnapshot.Domain<FactsSnapshot.IndexValuation> domain = snapshot.indexValuation();
        FactsSnapshot.IndexValuation v = domain.data();
        if (v == null) {
            return new CodeLookupV2DTO.ValuationView(null, null, null, null, null, null,
                    0, null, null, null, 0, domain.asOf(), domain.note());
        }
        return new CodeLookupV2DTO.ValuationView(
                v.source(),
                v.sourceLabel(),
                v.percentileMethod(),
                v.peTtm(),
                v.percentile(),
                v.historyFrom() == null ? null : v.historyFrom().toString(),
                v.historyLength() == null ? 0 : v.historyLength(),
                null,
                null,
                null,
                v.historyLength(),
                v.tradeDate() == null ? domain.asOf() : v.tradeDate().toString(),
                domain.note());
    }

    /** 最后一条「至少有一个正估值」的点；最后一行可能只有收盘价没有 PE。 */
    private static WarehouseRows.ValuationPoint latestUsable(List<WarehouseRows.ValuationPoint> points) {
        for (int i = points.size() - 1; i >= 0; i--) {
            WarehouseRows.ValuationPoint p = points.get(i);
            if (positive(p.peTtm()) != null || positive(p.pbMrq()) != null) {
                return p;
            }
        }
        return null;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    private static CodeLookupV2DTO.TechnicalView technicalView(TechnicalCalculator.TechnicalSummary t) {
        return new CodeLookupV2DTO.TechnicalView(t.barCount(),
                t.lastDate() == null ? null : t.lastDate().toString(), t.price(), t.hasOhlc(),
                t.ma20(), t.ma60(), t.ma120(), t.ma250(), t.atr14(),
                t.low20(), t.low60(), t.low120(), t.high1y(), t.low1y(),
                t.pricePercentile1y(), t.momentum20d(), t.momentum60d());
    }

    // ------------------------------------------------------------------
    // P4 分析视图
    // ------------------------------------------------------------------

    private static CodeLookupV2DTO.VerdictView verdictView(Verdict verdict) {
        return new CodeLookupV2DTO.VerdictView(
                verdict.label(), verdict.labelKey(), verdict.score(),
                verdict.scoreLow(), verdict.scoreHigh(), verdict.coverage(),
                verdict.confidence(), verdict.eventAdjustment(), verdict.hardFlags(),
                reasonViews(verdict.reasons()), reasonViews(verdict.risks()),
                verdict.withheldReason());
    }

    private static List<CodeLookupV2DTO.ReasonView> reasonViews(List<Reason> reasons) {
        List<CodeLookupV2DTO.ReasonView> out = new ArrayList<>();
        if (reasons == null) return out;
        for (Reason reason : reasons) {
            out.add(new CodeLookupV2DTO.ReasonView(reason.text(), reason.evidenceIds()));
        }
        return out;
    }

    private static List<CodeLookupV2DTO.DimensionView> dimensionViews(
            List<DimensionScore> dimensions) {
        List<CodeLookupV2DTO.DimensionView> out = new ArrayList<>();
        if (dimensions == null) return out;
        for (DimensionScore dimension : dimensions) {
            List<CodeLookupV2DTO.MetricView> metrics = new ArrayList<>();
            for (ScoreMetric metric : dimension.metrics()) {
                metrics.add(new CodeLookupV2DTO.MetricView(metric.key(), metric.label(),
                        metric.value(), metric.display(), metric.score(), metric.weight(),
                        metric.basis(), metric.evidenceId()));
            }
            out.add(new CodeLookupV2DTO.DimensionView(dimension.key(), dimension.label(),
                    dimension.weight(), dimension.score(), dimension.coverage(),
                    dimension.status(), List.copyOf(metrics), dimension.reasons()));
        }
        return out;
    }

    private static CodeLookupV2DTO.BuyZoneView buyZoneView(BuyZone zone) {
        return new CodeLookupV2DTO.BuyZoneView(zone.status(), zone.low(), zone.high(),
                zone.stopLoss(), zone.distanceToZonePct(), zone.riskPct(),
                reasonViews(zone.basis()), zone.notes());
    }

    private static List<CodeLookupV2DTO.ScenarioView> scenarioViews(List<Scenario> scenarios) {
        List<CodeLookupV2DTO.ScenarioView> out = new ArrayList<>();
        if (scenarios == null) return out;
        for (Scenario scenario : scenarios) {
            out.add(new CodeLookupV2DTO.ScenarioView(scenario.key(), scenario.label(),
                    scenario.triggered(), scenario.condition(), scenario.narrative(),
                    scenario.evidenceIds(), scenario.invalidation(), scenario.disclaimer()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // detail
    // ------------------------------------------------------------------

    public CodeLookupV2DTO.DetailView detail(String rawCode, String requestedSnapshotId) {
        requireReady();
        FactsSnapshot snapshot = factsService.snapshot(rawCode);
        boolean changed = requestedSnapshotId != null && !requestedSnapshotId.isBlank()
                && !requestedSnapshotId.equals(snapshot.snapshotId());

        CodeLookupV2DTO.Section<List<CodeLookupV2DTO.FinancialView>> financials =
                section(snapshot.financials(), CodeLookupV2Service::financialViews);
        CodeLookupV2DTO.Section<List<CodeLookupV2DTO.MoneyFlowView>> moneyflow =
                section(snapshot.moneyflow(), CodeLookupV2Service::moneyFlowViews);
        CodeLookupV2DTO.Section<CodeLookupV2DTO.IndexProfileView> profile =
                profileSection(snapshot.indexProfile());
        CodeLookupV2DTO.Section<List<CodeLookupV2DTO.IndustryView>> industry =
                industrySection(snapshot.industry());
        CodeLookupV2DTO.Section<List<CodeLookupV2DTO.EventView>> events =
                section(snapshot.events(), CodeLookupV2Service::eventViews);
        AnalysisResult analysis = analysisService.analyze(snapshot);

        List<String> notes = new ArrayList<>();
        if (changed) {
            notes.add("数据在两次请求之间已更新，请重新拉取首屏以保持结论与依据同一份快照");
        }
        notes.addAll(notes(snapshot));

        return new CodeLookupV2DTO.DetailView(snapshot.instrument().rawCode(),
                snapshot.snapshotId(), changed, financials, moneyflow, profile, industry,
                events, scenarioViews(analysis.scenarios()), List.copyOf(notes));
    }

    private static <T, R> CodeLookupV2DTO.Section<List<R>> section(
            FactsSnapshot.Domain<List<T>> domain,
            java.util.function.Function<List<T>, List<R>> mapper) {
        List<T> data = domain.data();
        List<R> mapped = data == null || data.isEmpty() ? List.of() : mapper.apply(data);
        return new CodeLookupV2DTO.Section<>(mapped, status(domain.status()), domain.note(),
                domain.asOf());
    }

    private static List<CodeLookupV2DTO.FinancialView> financialViews(
            List<WarehouseRows.FinancialReport> rows) {
        List<CodeLookupV2DTO.FinancialView> out = new ArrayList<>(rows.size());
        for (WarehouseRows.FinancialReport r : rows) {
            out.add(new CodeLookupV2DTO.FinancialView(
                    iso(r.reportDate()), r.reportType(), iso(r.announceDate()),
                    r.revenue(), r.revenueYoy(), r.netProfit(), r.netProfitYoy(),
                    r.roe(), r.grossMargin(), r.netMargin(), r.ocf(),
                    r.totalAssets(), r.totalEquity(), r.totalLiabilities(),
                    r.currentAssets(), r.currentLiabilities(), r.interestExpense(),
                    r.ebit(), r.goodwill(), r.accountsReceivable(), r.source()));
        }
        return out;
    }

    private static List<CodeLookupV2DTO.MoneyFlowView> moneyFlowViews(
            List<WarehouseRows.MoneyFlow> rows) {
        List<CodeLookupV2DTO.MoneyFlowView> out = new ArrayList<>(rows.size());
        for (WarehouseRows.MoneyFlow r : rows) {
            out.add(new CodeLookupV2DTO.MoneyFlowView(iso(r.tradeDate()), r.mainNet(),
                    r.superNet(), r.largeNet(), r.mediumNet(), r.smallNet(), r.mainRatio(),
                    r.source()));
        }
        return out;
    }

    private static CodeLookupV2DTO.Section<CodeLookupV2DTO.IndexProfileView> profileSection(
            FactsSnapshot.Domain<WarehouseRows.IndexProfile> domain) {
        WarehouseRows.IndexProfile p = domain.data();
        CodeLookupV2DTO.IndexProfileView view = p == null ? null
                : new CodeLookupV2DTO.IndexProfileView(p.indexCode(), p.name(), iso(p.asOf()),
                p.constituentCount(), p.top10Weight(), p.peTtm(), p.dividendYield(),
                p.totalMarketCap(), p.source());
        return new CodeLookupV2DTO.Section<>(view, status(domain.status()), domain.note(),
                domain.asOf());
    }

    private static CodeLookupV2DTO.Section<List<CodeLookupV2DTO.IndustryView>> industrySection(
            FactsSnapshot.Domain<List<WarehouseRows.IndustryWeight>> domain) {
        List<WarehouseRows.IndustryWeight> rows = domain.data();
        List<CodeLookupV2DTO.IndustryView> out = new ArrayList<>();
        if (rows != null) {
            for (WarehouseRows.IndustryWeight r : rows) {
                out.add(new CodeLookupV2DTO.IndustryView(r.industryCode(), r.industryName(),
                        r.weight(), iso(r.asOf())));
            }
        }
        return new CodeLookupV2DTO.Section<>(out, status(domain.status()), domain.note(),
                domain.asOf());
    }

    private static List<CodeLookupV2DTO.EventView> eventViews(List<WarehouseRows.Event> rows) {
        List<CodeLookupV2DTO.EventView> out = new ArrayList<>(rows.size());
        for (WarehouseRows.Event e : rows) {
            out.add(new CodeLookupV2DTO.EventView(e.category(), e.eventType(), e.title(),
                    e.summary(), e.url(), e.publishedAt() == null ? null : e.publishedAt().toString(),
                    e.source(), e.importance(), e.polarity()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // status
    // ------------------------------------------------------------------

    public CodeLookupV2DTO.StatusView status(String rawCode) {
        requireReady();
        FactsSnapshot snapshot = factsService.snapshot(rawCode);
        InstrumentCode instrument = snapshot.instrument();

        String task = instrument.isStock() ? "STOCK_DETAIL" : "INDEX_DETAIL";
        List<CodeLookupV2DTO.JobView> jobs = new ArrayList<>();
        repository.latestJob(instrument.key(), task).ifPresent(job -> jobs.add(jobView(job)));

        return new CodeLookupV2DTO.StatusView(
                instrument.rawCode(),
                snapshot.snapshotId(),
                snapshot.prices().asOf(),
                snapshot.instrument().isStock()
                        ? snapshot.valuations().asOf() : snapshot.indexValuation().asOf(),
                snapshot.financials().asOf(),
                snapshot.events().asOf(),
                snapshot.syncPending(),
                snapshot.retryAfterMs(),
                domainMap(snapshot),
                List.copyOf(jobs),
                LocalDateTime.now(SHANGHAI).toString());
    }

    private static CodeLookupV2DTO.JobView jobView(WarehouseRows.SyncJob job) {
        return new CodeLookupV2DTO.JobView(job.task(), job.status(), job.error(),
                job.startedAt() == null ? null : job.startedAt().toString(),
                job.finishedAt() == null ? null : job.finishedAt().toString(),
                job.retryAfter() == null ? null : job.retryAfter().toString());
    }

    // ------------------------------------------------------------------
    // search
    // ------------------------------------------------------------------

    public List<CodeLookupV2DTO.SearchHit> search(String query) {
        requireReady();
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        List<CodeLookupV2DTO.SearchHit> hits = new ArrayList<>();
        for (WarehouseRows.Instrument row : repository.searchInstruments(q, SEARCH_LIMIT)) {
            hits.add(new CodeLookupV2DTO.SearchHit(row.instrumentCode(), row.rawCode(),
                    row.name(), row.assetType(), row.market()));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // 旧页回放口径
    // ------------------------------------------------------------------

    /**
     * 8 档价格回放。**每一档都是「目标日那一天（或之前最近的一个交易日）vs 今天」**，
     * 不是区间涨幅，也不是「N 年前那一段的收益」。数据覆盖不到时保留标签、
     * 数值留空并写明原因——缺数据不补 0。
     */
    private static List<CodeLookupV2DTO.LookbackView> priceLookbacks(FactsSnapshot snapshot) {
        List<WarehouseRows.PriceBar> bars = ascendingPrices(snapshot.prices().data());
        if (bars.isEmpty()) {
            return List.of();
        }
        WarehouseRows.PriceBar last = bars.get(bars.size() - 1);
        BigDecimal current = last.closePrice();
        FactsSnapshot.Quote quote = snapshot.quote().data();
        if (quote != null && quote.price() != null) {
            current = quote.price();
        }
        List<CodeLookupV2DTO.LookbackView> out = new ArrayList<>();
        for (Lookback lookback : PRICE_LOOKBACKS) {
            LocalDate target = lookback.target(last.tradeDate());
            WarehouseRows.PriceBar baseline = baseline(bars, target);
            out.add(lookbackView(lookback.label(), baseline, current, baseline == null ? null
                    : baseline.closePrice(), "本地日线覆盖不到这一天"));
        }
        return List.copyOf(out);
    }

    /**
     * 个股 PE **分位**回放，与旧页同一口径：每一档是「那一天的分位 vs 今天的分位」，
     * 变化是**百分点差**（51 比 50 高 1 个点），不是相对涨幅。
     *
     * <p>指数/ETF 的 PE 历史在指数估值域，不在这个快照里——这里返回空列表，
     * 页面指引到「估值」标签页，而不是拿指数 PE 冒充个股。
     */
    private static List<CodeLookupV2DTO.LookbackView> peLookbacks(FactsSnapshot snapshot) {
        if (!snapshot.instrument().isStock()) {
            return List.of();
        }
        List<WarehouseRows.ValuationPoint> points = new ArrayList<>();
        if (snapshot.valuations().data() != null) {
            points.addAll(snapshot.valuations().data());
        }
        points.sort(java.util.Comparator.comparing(WarehouseRows.ValuationPoint::tradeDate));

        String shortHistoryNote = points.isEmpty()
                ? "本地还没有这只票的估值历史（东财估值分析源覆盖 2018 年起）"
                : "估值历史从 " + points.get(0).tradeDate() + " 开始，覆盖不到这一天";
        ValuationMath.Percentiles percentiles = ValuationMath.compute(points);
        if (percentiles.pe().isEmpty()) {
            return missingLookbacks(shortHistoryNote);
        }

        // 分位序列按日期升序；LookbackCalculator 再做一次清洗与排序，
        // 与旧页的八档（昨/周/月/半年/一年/三年/五年/十年）逐字一致。
        List<LookbackCalculator.Point> series = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : percentiles.pe().entrySet()) {
            series.add(new LookbackCalculator.Point(entry.getKey(), entry.getValue()));
        }
        LookbackCalculator.Result result = LookbackCalculator.compute(series,
                LookbackCalculator.ChangeStyle.POINTS, shortHistoryNote);
        List<CodeLookupV2DTO.LookbackView> out = new ArrayList<>();
        for (LookbackCalculator.Cell cell : result.cells()) {
            out.add(new CodeLookupV2DTO.LookbackView(cell.label(),
                    cell.baselineDate() == null ? null : cell.baselineDate().toString(),
                    cell.baseline(), cell.change(), cell.status()));
        }
        return List.copyOf(out);
    }

    private static List<CodeLookupV2DTO.LookbackView> missingLookbacks(String reason) {
        List<CodeLookupV2DTO.LookbackView> out = new ArrayList<>();
        for (String label : LookbackCalculator.LABELS) {
            out.add(new CodeLookupV2DTO.LookbackView(label, null, null, null, reason));
        }
        return List.copyOf(out);
    }

    private static List<WarehouseRows.PriceBar> ascendingPrices(
            List<WarehouseRows.PriceBar> bars) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        List<WarehouseRows.PriceBar> sorted = new ArrayList<>(bars);
        sorted.sort(java.util.Comparator.comparing(WarehouseRows.PriceBar::tradeDate));
        return sorted;
    }

    /**
     * 目标日当天或之前最近的一根日线；**距目标日超过 15 天就不算数**。
     * 这个 15 天与旧页 {@code LookbackCalculator.STALE_DAYS} 是同一个数：
     * 长期停牌时不能把三个月前的收盘价当成「昨」。
     */
    private static WarehouseRows.PriceBar baseline(List<WarehouseRows.PriceBar> bars,
                                                   LocalDate target) {
        for (int i = bars.size() - 1; i >= 0; i--) {
            WarehouseRows.PriceBar bar = bars.get(i);
            if (bar.tradeDate().isAfter(target)) {
                continue;
            }
            return java.time.temporal.ChronoUnit.DAYS.between(bar.tradeDate(), target)
                    > STALE_DAYS ? null : bar;
        }
        return null;
    }

    private static CodeLookupV2DTO.LookbackView lookbackView(
            String label, WarehouseRows.PriceBar baseline, BigDecimal current,
            BigDecimal baselineValue, String missingStatus) {
        if (baseline == null || baselineValue == null) {
            return new CodeLookupV2DTO.LookbackView(label, null, null, null, missingStatus);
        }
        return new CodeLookupV2DTO.LookbackView(label, baseline.tradeDate().toString(),
                baselineValue, percentChange(current, baselineValue), null);
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal baseline) {
        if (baseline == null || baseline.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(baseline)
                .multiply(BigDecimal.valueOf(100))
                .divide(baseline, 2, java.math.RoundingMode.HALF_UP);
    }

    private record Lookback(String label, java.util.function.Function<LocalDate, LocalDate> shift) {
        LocalDate target(LocalDate date) {
            return shift.apply(date);
        }
    }

    /**
     * 八档与旧页 {@link LookbackCalculator#LABELS} 逐字一致。
     * **顺序即版式**：改这里会一起改掉概览页那一行的顺序。
     */
    private static final List<Lookback> PRICE_LOOKBACKS = List.of(
            new Lookback("昨", date -> date.minusDays(1)),
            new Lookback("周", date -> date.minusDays(7)),
            new Lookback("月", date -> date.minusMonths(1)),
            new Lookback("半年", date -> date.minusMonths(6)),
            new Lookback("一年", date -> date.minusYears(1)),
            new Lookback("三年", date -> date.minusYears(3)),
            new Lookback("五年", date -> date.minusYears(5)),
            new Lookback("十年", date -> date.minusYears(10)));

    // ------------------------------------------------------------------
    // 状态映射
    // ------------------------------------------------------------------

    private static Map<String, CodeLookupV2DTO.DomainView> domainMap(FactsSnapshot snapshot) {
        Map<String, CodeLookupV2DTO.DomainView> data = new LinkedHashMap<>();
        data.put("quote", domainView(snapshot.quote()));
        data.put("prices", domainView(snapshot.prices()));
        data.put("valuations", domainView(snapshot.valuations()));
        data.put("indexValuation", domainView(snapshot.indexValuation()));
        data.put("financials", domainView(snapshot.financials()));
        data.put("moneyflow", domainView(snapshot.moneyflow()));
        data.put("indexProfile", domainView(snapshot.indexProfile()));
        data.put("industry", domainView(snapshot.industry()));
        data.put("events", domainView(snapshot.events()));
        return data;
    }

    private static CodeLookupV2DTO.DomainView domainView(FactsSnapshot.Domain<?> domain) {
        return new CodeLookupV2DTO.DomainView(status(domain.status()), domain.note(), domain.asOf());
    }

    /**
     * 只把「需要用户知道」的域说明收进顶层 notes。
     *
     * <p>状态为 OK 的域不重复写一遍——每段都在 {@code data} 里带了自己的状态，
     * 顶层再列一次只会把真正重要的「估值历史只有 300 条」淹掉。
     */
    private static List<String> notes(FactsSnapshot snapshot) {
        List<String> notes = new ArrayList<>();
        addNote(notes, snapshot.quote());
        addNote(notes, snapshot.prices());
        addNote(notes, snapshot.valuations());
        addNote(notes, snapshot.indexValuation());
        addNote(notes, snapshot.financials());
        addNote(notes, snapshot.moneyflow());
        addNote(notes, snapshot.indexProfile());
        addNote(notes, snapshot.industry());
        addNote(notes, snapshot.events());
        return List.copyOf(notes);
    }

    private static void addNote(List<String> notes, FactsSnapshot.Domain<?> domain) {
        if (domain.note() == null || domain.note().isBlank()) {
            return;
        }
        if (domain.status() == DataStatus.OK) {
            return;
        }
        if (!notes.contains(domain.note())) {
            notes.add(domain.note());
        }
    }

    private static String status(DataStatus status) {
        return status == null ? DataStatus.EMPTY.name() : status.name();
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    /**
     * v2 总开关 + 数据底座检查。
     *
     * <p>返回 503 而不是 500：这不是「代码有问题」，是「部署没完成」。
     * 关掉开关时 v1 完全不受影响，这也是回滚绳。
     */
    private void requireReady() {
        if (!properties.isEnabled()) {
            throw new WarehouseUnavailableException(
                    "代码查询 v2 已关闭（warehouse.enabled=false），请使用旧版页面");
        }
        if (!schemaGuard.isReady()) {
            throw new WarehouseUnavailableException(schemaGuard.unavailableReason());
        }
    }

}
