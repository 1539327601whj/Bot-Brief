package com.ai.daily.screener;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「低估精选」的编排层。规则本身在 {@link ScreeningRules}（纯函数），
 * 外呼在 {@link MarketDataClient}，缓存与单飞在 {@link ScreenerCache}。
 *
 * <p>流程：缓存/抓取全市场快照 → 排雷 → 基本面短名单 → **并发**抓日线 → 带价格位置定稿 →
 * 指数基金块 → 组装口径摘要。
 *
 * <p><b>不落库、不推送、不定时。</b>每次调用都是一次独立的实时计算。
 */
@Slf4j
@Service
public class StockScreenerService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 全结果同行业最多 2 只，避免整页其实是同一个宏观押注。 */
    static final int INDUSTRY_MAX_TOTAL = 2;
    /** 每档同行业最多 1 只。 */
    static final int INDUSTRY_MAX_PER_BUCKET = 1;

    static final String DISCLAIMER =
            "本页仅为公开行情数据的规则化筛选结果，是待核实的观察清单，"
                    + "不构成投资建议、证券推荐或买卖依据。所有数字来自公开行情接口，"
                    + "可能因财报期、复权口径、停牌等原因与实际不符，请自行核实后再做判断。";

    private final MarketDataClient marketDataClient;
    private final ScreenerCache cache;
    private final MarketValuationHistoryService valuationHistoryService;
    private final ExecutorService fetchPool;

    public StockScreenerService(
            MarketDataClient marketDataClient,
            ScreenerCache cache,
            MarketValuationHistoryService valuationHistoryService,
            @Value("${screener.fetch-threads:5}") int fetchThreads) {
        this.marketDataClient = marketDataClient;
        this.cache = cache;
        this.valuationHistoryService = valuationHistoryService;
        int threads = Math.max(2, Math.min(fetchThreads, 8));
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "screener-fetch-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.fetchPool = Executors.newFixedThreadPool(threads, factory);
    }

    @PreDestroy
    void shutdown() {
        fetchPool.shutdownNow();
    }

    /**
     * 跑一次筛选。参数为 null 即使用默认条件。
     *
     * @throws MarketDataException 行情源不可达——**必须让用户看到错误，不能返回空列表假装「今天没有候选」**
     */
    public ScreenerResultDTO scan(ScreenerParams rawParams) {
        ScreenerParams p = rawParams == null ? ScreenerParams.defaults() : rawParams.normalized();
        LocalDate today = LocalDate.now(SHANGHAI);
        List<String> degradations = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        List<StockRow> universe = new ArrayList<>();
        MarketDataClient.UniverseSnapshot snapshot = null;
        if (p.wantsStocks()) {
            snapshot = loadUniverse(degradations);
            universe = snapshot.rows();
            if (universe == null || universe.isEmpty()) {
                throw new MarketDataException("行情源返回了空的股票列表，本次筛选未执行");
            }
            describePool(snapshot, notes, degradations);
        } else {
            notes.add("本次只筛指数基金，未拉取全市场个股快照");
        }

        // ---- 排雷 ----
        List<StockRow> steadyPool = new ArrayList<>();
        List<StockRow> growthPool = new ArrayList<>();
        Map<String, Integer> vetoCounts = new LinkedHashMap<>();
        Map<String, String> vetoLabels = new LinkedHashMap<>();
        int afterVetoes = 0;

        for (StockRow row : universe) {
            boolean passSteady = false;
            boolean passGrowth = false;
            List<ScreeningRules.Veto> firstVeto = null;
            if (p.wantsSteady()) {
                List<ScreeningRules.Veto> v = ScreeningRules.vetoes(row, ScreeningRules.Bucket.STEADY, p, today);
                if (v.isEmpty()) {
                    passSteady = true;
                    steadyPool.add(row);
                } else if (firstVeto == null) {
                    firstVeto = v;
                }
            }
            if (p.wantsGrowth()) {
                List<ScreeningRules.Veto> v = ScreeningRules.vetoes(row, ScreeningRules.Bucket.GROWTH, p, today);
                if (v.isEmpty()) {
                    passGrowth = true;
                    growthPool.add(row);
                } else if (firstVeto == null) {
                    firstVeto = v;
                }
            }
            if (passSteady || passGrowth) {
                afterVetoes++;
            } else if (firstVeto != null) {
                ScreeningRules.Veto v = firstVeto.get(0);
                vetoCounts.merge(v.rule(), 1, Integer::sum);
                vetoLabels.putIfAbsent(v.rule(), v.label());
            }
        }

        // 行业不可确认的数量要公开，否则「行业分散」这条约束看起来像没生效
        long unknownIndustry = universe.stream().filter(StockRow::isIndustryUnknown).count();
        if (unknownIndustry > 0) {
            degradations.add(unknownIndustry + " 只标的行业字段缺失，已归入「行业不可确认」参与行业分散，且不参与金融地产排除判断");
        }

        // ---- 短名单 ----
        List<StockRow> steadyShort = p.wantsSteady()
                ? ScreeningRules.shortlist(steadyPool, ScreeningRules.Bucket.STEADY, p) : List.of();
        List<StockRow> growthShort = p.wantsGrowth()
                ? ScreeningRules.shortlist(growthPool, ScreeningRules.Bucket.GROWTH, p) : List.of();

        Set<String> shortCodes = new LinkedHashSet<>();
        steadyShort.forEach(r -> shortCodes.add(r.getCode()));
        growthShort.forEach(r -> shortCodes.add(r.getCode()));
        notes.add("排雷后用基本面指标取每档前 " + ScreeningRules.SHORTLIST_PER_BUCKET
                + " 只去抓日线（本批去重后 " + shortCodes.size() + " 只），再带价格位置重算一遍得分");

        // ---- 并发抓日线（个股 + 指数基金一次抓完）----
        Map<String, String> secidByCode = new LinkedHashMap<>();
        for (StockRow r : steadyShort) secidByCode.put(r.getCode(), r.secid());
        for (StockRow r : growthShort) secidByCode.putIfAbsent(r.getCode(), r.secid());
        for (IndexFundPool.Fund f : IndexFundPool.FUNDS) secidByCode.put(f.code(), f.secid());

        Map<String, MarketDataClient.KlineOutcome> klines = fetchKlines(secidByCode, degradations);
        Map<String, PricePosition> positions = new HashMap<>();
        klines.forEach((code, outcome) -> positions.put(code,
                outcome.ok() ? PricePositionCalculator.compute(outcome.bars())
                        : PricePosition.unavailable(outcome.failureReason())));

        // ---- 定稿 ----
        Map<String, Integer> industryUsed = new HashMap<>();
        Set<String> usedCodes = new HashSet<>();

        ScreeningRules.Selection steadySel = p.wantsSteady()
                ? ScreeningRules.select(steadyShort, ScreeningRules.Bucket.STEADY, p, positions,
                new ScreeningRules.Constraints(p.getPerBucket(), Set.of(), industryUsed,
                        INDUSTRY_MAX_PER_BUCKET, INDUSTRY_MAX_TOTAL))
                : new ScreeningRules.Selection(List.of(), 0, 0);
        List<ScreeningRules.Selected> steady = steadySel.picked();
        steady.forEach(s -> {
            usedCodes.add(s.row().getCode());
            industryUsed.merge(ScreeningRules.industryKey(s.row()), 1, Integer::sum);
        });

        ScreeningRules.Selection growthSel = p.wantsGrowth()
                ? ScreeningRules.select(growthShort, ScreeningRules.Bucket.GROWTH, p, positions,
                new ScreeningRules.Constraints(p.getPerBucket(), usedCodes, industryUsed,
                        INDUSTRY_MAX_PER_BUCKET, INDUSTRY_MAX_TOTAL))
                : new ScreeningRules.Selection(List.of(), 0, 0);
        List<ScreeningRules.Selected> growth = growthSel.picked();

        int requested = (p.wantsSteady() ? p.getPerBucket() : 0) + (p.wantsGrowth() ? p.getPerBucket() : 0);
        int got = steady.size() + growth.size();
        if (p.wantsStocks() && got < requested) {
            notes.add("本次合格候选不足：条件内只选出 " + got + " 只（最多 " + requested
                    + " 只）。宁可少给，不凑数。");
        }
        int belowFloor = steadySel.belowFloor() + growthSel.belowFloor();
        if (p.wantsStocks() && belowFloor > 0) {
            notes.add("另有 " + belowFloor + " 只通过了全部排雷，但综合分低于下限 "
                    + ScreeningRules.SCORE_FLOOR.stripTrailingZeros().toPlainString()
                    + "。本页用的是「同批候选内的相对分位」，这条下限等价于"
                    + "「没有明显好于本批中位」，所以它们没进结果。");
        }
        int industryBlocked = steadySel.skippedByIndustry() + growthSel.skippedByIndustry();
        if (p.wantsStocks() && industryBlocked > 0) {
            notes.add("另有 " + industryBlocked + " 只因行业集中度限制（每档同行业最多 1 只、"
                    + "全部结果同行业最多 " + INDUSTRY_MAX_TOTAL + " 只）被排除。");
        }

        // ---- 指数基金块 ----
        List<ScreenerResultDTO.IndexFundItem> indexFunds = p.wantsIndexFunds()
                ? buildIndexFunds(positions, degradations)
                : List.of();

        // ---- 口径摘要 ----
        List<ScreeningRules.VetoCount> counts = new ArrayList<>();
        vetoCounts.forEach((rule, n) -> counts.add(
                new ScreeningRules.VetoCount(rule, vetoLabels.getOrDefault(rule, rule), n)));
        counts.sort(Comparator.comparing(ScreeningRules.VetoCount::rule));

        int enteredScoring = afterVetoes;
        int counted = counts.stream().mapToInt(ScreeningRules.VetoCount::count).sum();
        if (p.wantsStocks() && counted + enteredScoring != universe.size()) {
            // 自洽性是硬要求，对不上就在页面上说出来，不要静默
            degradations.add("口径摘要对不上：剔除 " + counted + " + 进入打分 " + enteredScoring
                    + " ≠ 扫描 " + universe.size() + "，请以扫描总数为准并反馈");
        }

        List<String> summaryNotes = new ArrayList<>(notes);
        summaryNotes.add("行情快照取自东财公开接口，日线为前复权口径（除权日不会显示假跌）");
        summaryNotes.add("不同公司的最新财报期可能不一致，同比数据不宜横向直接比较");
        summaryNotes.add("金融、地产因资产负债率口径与制造业不同而整体不纳入，剔除数量见上方");

        LocalDate priceAsOf = positions.values().stream()
                .filter(PricePosition::isAvailable)
                .map(PricePosition::getLastTradeDate)
                .filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        return new ScreenerResultDTO(
                LocalDateTime.now(SHANGHAI).format(TS),
                priceAsOf == null ? "未取到" : priceAsOf.toString(),
                p,
                new ScreenerResultDTO.Summary(
                        universe.size(), afterVetoes, steadyPool.size(), growthPool.size(),
                        secidByCode.size(), counts, degradations, summaryNotes),
                indexFunds, steady, growth,
                DISCLAIMER);
    }

    // ==================================================================

    /**
     * 取全市场快照，并把「被限流」翻译成一句用户能照做的话。
     *
     * <p>冷却期里如果手上还留着一份快照，就用它——带着明确的数据时间端出来，
     * 比一个错误框有用得多。真正一次都没取到过，才如实报错。
     */
    private MarketDataClient.UniverseSnapshot loadUniverse(List<String> degradations) {
        if (cache.universeFresh()) {
            return cache.universe(marketDataClient::fetchUniverse);
        }
        if (cache.inCooldown()) {
            Optional<MarketDataClient.UniverseSnapshot> stale = cache.universeOrStale();
            if (stale.isPresent()) {
                degradations.add(staleNote());
                return stale.get();
            }
            throw rateLimited();
        }
        try {
            return cache.universe(marketDataClient::fetchUniverse);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            // 先记冷却再决定给什么：无论这次是报错还是拿旧快照兜底，
            // 下一次点击都不该再往外打。
            cache.enterCooldown();
            log.warn("行情源限流：{}", e.getMessage());
            Optional<MarketDataClient.UniverseSnapshot> stale = cache.universeOrStale();
            if (stale.isPresent()) {
                degradations.add(staleNote());
                return stale.get();
            }
            throw rateLimited();
        }
    }

    private String staleNote() {
        String takenAt = cache.universeTakenAt()
                .map(t -> t.format(TS)).orElse("较早时间");
        return "行情源正在限流，本次没有重新拉取全市场快照，用的是 " + takenAt
                + " 那一份。价格与估值在这之后可能已经变化。";
    }

    private MarketDataException.MarketDataRateLimitedException rateLimited() {
        String wait = cache.cooldownRemainingMinutes() > 0
                ? "大约 " + cache.cooldownRemainingMinutes() + " 分钟"
                : "几分钟";
        return new MarketDataException.MarketDataRateLimitedException(
                "东财行情接口正在限流这个 IP，需要等 " + wait + " 再试。"
                        + "这不是网络故障，也不用改设置——它按 IP 计时，"
                        + "短时间内连续刷新太多就会触发，等一会儿会自己恢复。", null);
    }

    /** 把池子是怎么来的写进口径摘要：只看总市值前 N 只这件事，必须让人看得见。 */
    private void describePool(MarketDataClient.UniverseSnapshot snapshot,
                              List<String> notes, List<String> degradations) {
        if (snapshot.tradeDate() != null) {
            notes.add("清单所属交易日 " + snapshot.tradeDate());
        }
        String floor = snapshot.capFloor() == null ? "未知"
                : snapshot.capFloor().divide(BigDecimal.valueOf(100000000L), 0, RoundingMode.HALF_UP)
                .toPlainString() + " 亿";
        notes.add("这一次的池子是「最新交易日总市值前 " + snapshot.listedCount()
                + " 只」，对应市值下限约 " + floor
                + "。市值更小的标的本次不在视野内，所以「扫描总数」不是全市场股票数。");
        if (snapshot.missing() > 0) {
            degradations.add(snapshot.missing()
                    + " 只在清单里但没取到对应行情，已从池子中剔除（不参与排雷计数）");
        }
    }

    private Map<String, MarketDataClient.KlineOutcome> fetchKlines(
            Map<String, String> secidByCode, List<String> degradations) {
        // 返回给上层用 code 索引（业务层只认代码），缓存用 secid 索引（东财只认 secid）。
        // 两者混用会让缓存永远不命中，每次点击都重新外呼一遍。
        Map<String, MarketDataClient.KlineOutcome> out = new LinkedHashMap<>();
        List<Map.Entry<String, String>> pending = new ArrayList<>();
        for (Map.Entry<String, String> e : secidByCode.entrySet()) {
            var cached = cache.kline(e.getValue());
            if (cached.isPresent()) out.put(e.getKey(), cached.get());
            else pending.add(e);
        }
        if (pending.isEmpty()) return out;

        List<Callable<Map.Entry<String, MarketDataClient.KlineOutcome>>> tasks = new ArrayList<>();
        for (Map.Entry<String, String> e : pending) {
            tasks.add(() -> Map.entry(e.getValue(), marketDataClient.fetchKline(e.getValue())));
        }
        Map<String, MarketDataClient.KlineOutcome> bySecid = new LinkedHashMap<>();
        try {
            List<Future<Map.Entry<String, MarketDataClient.KlineOutcome>>> futures = fetchPool.invokeAll(tasks);
            for (Future<Map.Entry<String, MarketDataClient.KlineOutcome>> f : futures) {
                try {
                    Map.Entry<String, MarketDataClient.KlineOutcome> r = f.get();
                    bySecid.put(r.getKey(), r.getValue());
                } catch (Exception e) {
                    log.debug("日线任务异常：{}", e.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketDataException("日线抓取被中断，请重试");
        }
        cache.putKlines(bySecid);
        for (Map.Entry<String, String> e : pending) {
            MarketDataClient.KlineOutcome outcome = bySecid.get(e.getValue());
            if (outcome != null) out.put(e.getKey(), outcome);
        }

        // 限流导致的失败单独算：它要触发冷却，不能和「这只票的日线坏了」混为一谈。
        long throttled = out.values().stream()
                .filter(MarketDataClient.KlineOutcome::throttled).count();
        if (throttled > 0) {
            cache.enterCooldown();
            degradations.add(throttled + " 只标的的日线被行情源限流拒绝，这些标的保留基本面得分、"
                    + "丢掉「价格位置」维并已重新归一权重，未因此被淘汰。"
                    + "已进入冷却，短时间内再点不会重新外呼。");
        }
        long failed = out.values().stream()
                .filter(o -> !o.ok() && !o.throttled()).count();
        if (failed > 0) {
            degradations.add(failed + " 只标的的日线未取到，这些标的保留基本面得分、"
                    + "丢掉「价格位置」维并已重新归一权重，未因此被淘汰");
        }
        return out;
    }

    /** 指数基金块：价格位置与规模来自行情/日线；PE 分位只读项目自己的库。 */
    private List<ScreenerResultDTO.IndexFundItem> buildIndexFunds(
            Map<String, PricePosition> positions, List<String> degradations) {
        List<String> secids = IndexFundPool.FUNDS.stream().map(IndexFundPool.Fund::secid).toList();
        Map<String, MarketDataClient.EtfQuote> quotes = marketDataClient.fetchEtfQuotes(secids);
        if (quotes.isEmpty()) {
            degradations.add("指数基金批量行情未取到，指数块只显示价格位置，规模与当日成交额未确认");
        }

        List<ScreenerResultDTO.IndexFundItem> items = new ArrayList<>();
        for (IndexFundPool.Fund f : IndexFundPool.FUNDS) {
            MarketDataClient.EtfQuote q = quotes.get(f.code());
            PricePosition pos = positions.get(f.code());
            List<String> notes = new ArrayList<>();

            if (q == null) notes.add("未取到行情，价格与成交额未确认");
            if (pos == null || !pos.isAvailable()) {
                notes.add(pos == null ? "未抓取日线，价格位置未确认" : String.join("；", pos.getDegradations()));
            } else if (PricePositionCalculator.isStale(pos, LocalDate.now(SHANGHAI))) {
                notes.add("日线最后一根为 " + pos.getLastTradeDate() + "，可能停牌或数据未更新");
            }

            BigDecimal peTtm = null;
            BigDecimal pePercentile = null;
            String percentileMethod = null;
            String percentileStatus;
            LocalDate valuationDate = null;

            if (f.valuationIndexCode() == null) {
                percentileStatus = IndexFundPool.PERCENTILE_NOT_WIRED;
                notes.add("该指数的 PE 历史分位尚未接入，本页不对其估值高低作任何判断");
            } else {
                percentileMethod = f.percentileMethod();
                List<MarketValuationHistory> rows;
                try {
                    rows = valuationHistoryService.latest(f.valuationIndexCode(), percentileMethod, 1);
                } catch (RuntimeException e) {
                    rows = List.of();
                    log.debug("读 {} 估值分位失败：{}", f.valuationIndexCode(), e.toString());
                }
                if (rows.isEmpty()) {
                    percentileStatus = IndexFundPool.PERCENTILE_NOT_WIRED;
                    notes.add("库里暂无 " + f.tracking() + " 的估值分位记录");
                } else {
                    MarketValuationHistory h = rows.get(0);
                    peTtm = h.getPeTtm();
                    pePercentile = h.getPePercentile();
                    valuationDate = h.getTradeDate();
                    percentileStatus = "已接入（" + percentileMethod + "）";
                    notes.add("PE 分位为 " + f.tracking() + " 指数口径，不是该 ETF 自身的分位，"
                            + "且不可与其他指数的 PE 直接比较");
                }
            }

            items.add(new ScreenerResultDTO.IndexFundItem(
                    f.code(), f.name(), f.tracking(),
                    q == null ? null : q.price(),
                    q == null ? null : q.pctChange(),
                    yi(q == null ? null : q.amount()),
                    yi(q == null ? null : q.marketCap()),
                    pos == null ? null : pos.getPricePercentile(),
                    pos == null ? null : pos.getDrawdownFromHigh(),
                    pos == null ? null : pos.getMaxDrawdownInYear(),
                    pos == null ? null : pos.getAnnualizedVolatility(),
                    pos == null ? null : pos.getVsMa250(),
                    pos == null ? null : pos.getBarCount(),
                    pos == null ? null : pos.getLastTradeDate(),
                    peTtm, pePercentile, percentileMethod, percentileStatus, valuationDate,
                    notes));
        }
        return items;
    }

    private static BigDecimal yi(BigDecimal yuan) {
        return yuan == null ? null : yuan.divide(BigDecimal.valueOf(100000000L), 2, RoundingMode.HALF_UP);
    }
}