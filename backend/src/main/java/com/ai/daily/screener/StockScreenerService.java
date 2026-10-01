package com.ai.daily.screener;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.entity.EtfPriceHistory;
import com.ai.daily.service.EtfPriceHistoryService;
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
 * 主源外呼在 {@link MarketDataClient}、兜底源在 {@link AltQuoteSource}，
 * 缓存与单飞在 {@link ScreenerCache}。
 *
 * <p>流程：缓存/抓取全市场快照 → 排雷 → 基本面短名单 → **并发**抓日线 → 带价格位置定稿 →
 * 指数基金块 → 组装口径摘要。
 *
 * <p><b>取数兜底链</b>：指数行情走 东财 → 腾讯 → 新浪，指数日线走
 * 内存缓存 → **本地预取库** → 东财 → 腾讯（有额度上限）。
 * 每层只补上一层缺的那几只；用了哪几个源写进口径摘要，不藏在代码里。
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

    /**
     * 读本地日线库时往前取多少个自然日。250 个交易日 ≈ 365 个自然日，
     * 这里留足春节这类长假与停牌串的余量；再长只是让「尾部连续段长度」这个次要判据更准，
     * 而它只在两个源打平时才起作用。取不到更早的不会报错，只是曲线短一截。
     */
    static final int DB_LOOKBACK_DAYS = 500;

    static final String DISCLAIMER =
            "本页仅为公开行情数据的规则化筛选结果，是待核实的观察清单，"
                    + "不构成投资建议、证券推荐或买卖依据。所有数字来自公开行情接口，"
                    + "可能因财报期、复权口径、停牌等原因与实际不符，请自行核实后再做判断。";

    private final MarketDataClient marketDataClient;
    private final ScreenerCache cache;
    private final MarketValuationHistoryService valuationHistoryService;
    private final EtfPriceHistoryService etfPriceHistoryService;
    private final IndexFundPool indexPool;
    private final AltQuoteSource altQuoteSource;
    private final int indexPoolMaxLiveFetches;
    private final ExecutorService fetchPool;

    public StockScreenerService(
            MarketDataClient marketDataClient,
            ScreenerCache cache,
            MarketValuationHistoryService valuationHistoryService,
            EtfPriceHistoryService etfPriceHistoryService,
            IndexFundPool indexPool,
            AltQuoteSource altQuoteSource,
            @Value("${screener.fetch-threads:5}") int fetchThreads,
            @Value("${screener.index-pool-max-live-fetches:10}") int indexPoolMaxLiveFetches) {
        this.marketDataClient = marketDataClient;
        this.cache = cache;
        this.valuationHistoryService = valuationHistoryService;
        this.etfPriceHistoryService = etfPriceHistoryService;
        this.indexPool = indexPool;
        this.altQuoteSource = altQuoteSource;
        // 兜底额度：池子扩到 200 条时，「每只一条日线」的外呼量正是把 IP 封得更深的做法。
        // 这个数字是安全阀，**不要因为「反正有兜底」就调大**。
        this.indexPoolMaxLiveFetches = Math.max(0, indexPoolMaxLiveFetches);
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
        for (IndexFundPool.Fund f : indexPool.withEtf()) secidByCode.put(f.etfCode(), f.secid());

        Map<String, MarketDataClient.KlineOutcome> klines = fetchKlines(secidByCode, degradations, notes);
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
                ? buildIndexFunds(positions, klines, degradations, notes)
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
        summaryNotes.add("行情快照取自东财公开接口，日线为前复权口径（除权日不会显示假跌）；"
                + "指数行情与指数日线在必要处用腾讯/新浪兜底，本次实际用到哪些见上方");
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
            Map<String, String> secidByCode, List<String> degradations, List<String> notes) {
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

        // ---- 先吃本地预取库（每日同步写入），这是点击时的主力：一次批量查询，零外呼 ----
        pending = loadIndexBarsFromDb(out, pending, secidByCode, notes);
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

        // ---- 兜底：东财没给到的那几只指数 ETF 改问腾讯 ----
        // 东财被限流时更要往这儿走：腾讯是另一家公司、另一份配额，
        // 「换一家」在这里正是全部意义（与 MarketDataClient 那条「失败不跨域名重试」并不矛盾——
        // 那条管的是同一个源内部换域名，换域名救不了按 IP 计的封禁）。
        Map<String, MarketDataClient.KlineOutcome> fromFallback =
                fetchKlinesFromTencent(out, pending, secidByCode, degradations);
        long recovered = fromFallback.values().stream()
                .filter(MarketDataClient.KlineOutcome::ok).count();
        if (recovered > 0) {
            notes.add("其中 " + recovered + " 只的日线来自腾讯兜底源（同为前复权口径）");
        }

        // 限流信号要**分两批**收，只看最终的 out 会漏：
        //
        // * 兜底成功会把 out 里那条「东财限流」换成一条正常结果。若不先记下主源那一批，
        //   东财的限流就凭空消失了——于是下一轮点击又去打东财，把按 IP 计的封禁推得更深。
        //   这恰恰是这个类最在意的那件事，不能因为腾讯救回来了就把它忘掉。
        // * 兜底源自己的限流只存在于它返回的那一批里（out 里留下的还是主源那条）。
        //
        // 冷却**按 provider 记**：腾讯挨的限流不该锁掉东财，反之亦然。
        Map<String, Long> throttledByProvider = new LinkedHashMap<>();
        countThrottled(bySecid.values(), throttledByProvider);
        countThrottled(fromFallback.values(), throttledByProvider);
        throttledByProvider.forEach(cache::enterCooldown);

        // 「被限流」与「这只票的日线坏了」是对**最终结果**的一次划分，一个代码只进一边，
        // 否则同一只会被算两遍、摘要里的数字加起来超过总数。
        long throttledFinal = out.values().stream()
                .filter(MarketDataClient.KlineOutcome::throttled).count();
        if (!throttledByProvider.isEmpty()) {
            degradations.add(throttledByProvider.values().stream().mapToLong(Long::longValue).sum()
                    + " 只标的的日线被行情源限流拒绝（" + describeCountByProvider(throttledByProvider) + "）。"
                    // 被限流但最终由兜底源救回来的，不能跟着说「丢掉了价格位置」——它没丢
                    + (throttledFinal > 0
                    ? "其中 " + throttledFinal + " 只最终仍没有日线，这些标的保留基本面得分、"
                    + "丢掉「价格位置」维并已重新归一权重，未因此被淘汰。"
                    : "")
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

    /** 按 provider 累计被限流的只数。 */
    private static void countThrottled(
            java.util.Collection<MarketDataClient.KlineOutcome> outcomes, Map<String, Long> into) {
        for (MarketDataClient.KlineOutcome o : outcomes) {
            if (o != null && o.throttled()) into.merge(providerOf(o), 1L, Long::sum);
        }
    }

    /**
     * 日线首选层：从本地预取库（{@code etf_price_history}）读指数池 ETF 的日线。
     *
     * <p><b>一次查询覆盖整池，零外呼。</b>这是 200 条池子唯一跑得动的取数方式——
     * 「每只一条日线」在点击时做 200 次 {@code push2his}，正是把 IP 封得更深的做法。
     * 日线由每日同步预取进库，点击时只读库；本地没有的才轮到外呼，且外呼有额度上限。
     *
     * <p><b>选源交给 {@link EtfPriceSeriesSelector}</b>：库的唯一键含 {@code source}，
     * 同一只 ETF 的同一天可能同时有东财行与腾讯行，直接按日期铺开会**静默换源**。
     * 那是本类最该防住的一类错误——曲线每一根都合法，算出来的分位却是错的。
     *
     * <p><b>读库失败不等于「没有日线」</b>：这里刻意**不吞掉后续步骤**。
     * 库连不上时把这几只退回给外呼那条链，而不是让整块价格位置变空——
     * 但也不假装成功，日志里记一条。
     *
     * <p><b>留给 阶段 6 的一处缺口</b>：本地库成为主源之后，「曲线有多旧」就从偶发变成常态。
     * 现在只靠 {@link PricePositionCalculator#isStale}（最后一根超过 15 个自然日）把陈旧判出来，
     * 而指数卡片**根本没渲染 {@code lastTradeDate}**——于是每日同步悄悄停掉时，
     * 页面上会是「看起来正常、其实是几周前的价格位置」，直到第 15 天才冒出一句提示。
     * 不去猜「几天算旧」（交易日历在 index_only 模式下拿不到，猜出来的阈值会在春节误报），
     * 而是让卡片**把日期和来源印出来**（`日线截止 {lastTradeDate}` + 价格位置来源）。
     * 事实先摆出来，判断留给看的人。
     *
     * @return 还没拿到日线的那些条目（顺序不变），交给后面的外呼链
     */
    private List<Map.Entry<String, String>> loadIndexBarsFromDb(
            Map<String, MarketDataClient.KlineOutcome> out,
            List<Map.Entry<String, String>> pending,
            Map<String, String> secidByCode,
            List<String> notes) {
        Set<String> indexCodes = new LinkedHashSet<>();
        for (IndexFundPool.Fund f : indexPool.withEtf()) indexCodes.add(f.etfCode());

        List<String> wanted = new ArrayList<>();
        for (Map.Entry<String, String> e : pending) {
            if (indexCodes.contains(e.getKey())) wanted.add(e.getKey());
        }
        if (wanted.isEmpty()) return pending;

        Map<String, List<EtfPriceHistory>> rowsByCode;
        try {
            rowsByCode = etfPriceHistoryService
                    .latestBatch(wanted, LocalDate.now(SHANGHAI).minusDays(DB_LOOKBACK_DAYS),
                            EtfPriceHistoryService.QFQ)
                    .stream()
                    .collect(java.util.stream.Collectors.groupingBy(EtfPriceHistory::getFundCode));
        } catch (RuntimeException e) {
            log.warn("读本地 ETF 日线库失败，这批改走外呼兜底链：{}", e.toString());
            return pending;
        }

        List<Map.Entry<String, String>> rest = new ArrayList<>();
        Map<String, MarketDataClient.KlineOutcome> toCache = new LinkedHashMap<>();
        int fromLocal = 0;
        for (Map.Entry<String, String> e : pending) {
            List<EtfPriceHistory> rows = indexCodes.contains(e.getKey()) ? rowsByCode.get(e.getKey()) : null;
            List<PricePositionCalculator.Bar> bars = rows == null
                    ? List.of()
                    : EtfPriceSeriesSelector.select(toPoints(rows), marketDataClient.klineLimit());
            if (bars.isEmpty()) {
                rest.add(e);
                continue;
            }
            MarketDataClient.KlineOutcome outcome =
                    MarketDataClient.KlineOutcome.ok(bars, MarketDataClient.PROVIDER_LOCAL);
            out.put(e.getKey(), outcome);
            // 存回缓存的键仍然是 secid：缓存的键回答「这是哪个标的」，源记在 outcome 里。
            // 只有成功的结果才写缓存——「本地库里没有」不该被缓存成一条失败，
            // 否则下次点击连外呼都不会试。
            toCache.put(secidByCode.get(e.getKey()), outcome);
            fromLocal++;
        }
        if (!toCache.isEmpty()) cache.putKlines(toCache);
        if (fromLocal > 0) {
            // 不写「其中 N 只」：上面没有总数，那句话会变成没有指代对象的一句。
            // 想说清的是「点击时这一批没有外呼」——这正是池子扩到 200 条的前提
            notes.add("指数池 " + fromLocal + " 只的日线取自本地预取库（每日同步写入，本次未外呼）");
        }
        return rest;
    }

    /** 库行 → 选源用的点。开高低量不参与价格位置，所以只带日期、收盘与来源。 */
    private static List<EtfPriceSeriesSelector.Point> toPoints(List<EtfPriceHistory> rows) {
        List<EtfPriceSeriesSelector.Point> points = new ArrayList<>(rows.size());
        for (EtfPriceHistory r : rows) {
            points.add(new EtfPriceSeriesSelector.Point(
                    r.getTradeDate(), r.getClose(), r.getSource(), r.getAdjustmentType()));
        }
        return points;
    }

    /**
     * 日线兜底：东财没给到的**指数池 ETF** 改问腾讯。
     *
     * <p><b>额度只覆盖指数池。</b>池子会扩到 200 条，而「每只一条日线」正是把 IP 封得更深的做法，
     * 所以这里必须有个上限；个股那边只数由短名单天然限住、且已经带着明确原因降级，
     * 不占这个额度。额度用完的要说出来，不能悄悄少几只。
     *
     * <p>成功的结果**按 secid 存回缓存**：缓存的键回答的是「这是哪个标的」，
     * 不是「哪个源给的」，源记在 {@code KlineOutcome.provider} 里。
     * 否则下一次点击又会因为「东财那个键底下存的是失败」而重新外呼一遍。
     *
     * <p><b>只有成功的结果会覆盖主源那条。</b>兜底也失败时，主源那句「被限流」才是更准确的
     * 解释（「东财限流，腾讯也没有」），把它换成一句「腾讯日线为空」等于把限流这件事说丢了。
     *
     * <p>这是**最后一层**：本地预取库（{@link #loadIndexBarsFromDb}）已经先吃过一遍，
     * 走到这里的是「库里也没有」的那些（新进池子、或预取还没跑）。所以这个额度天然很小，
     * 也正因如此它才敢小——正常运行时整池日线根本不需要外呼。
     *
     * @return 兜底真正返回的结果（没触发兜底时为空；调用方据此做限流计数与「来自腾讯」的说明）
     */
    private Map<String, MarketDataClient.KlineOutcome> fetchKlinesFromTencent(
            Map<String, MarketDataClient.KlineOutcome> out,
            List<Map.Entry<String, String>> pending,
            Map<String, String> secidByCode,
            List<String> degradations) {
        if (cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)) return Map.of();

        Set<String> indexCodes = new LinkedHashSet<>();
        for (IndexFundPool.Fund f : indexPool.withEtf()) indexCodes.add(f.etfCode());

        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> e : pending) {
            if (!indexCodes.contains(e.getKey())) continue;
            MarketDataClient.KlineOutcome o = out.get(e.getKey());
            if (o != null && o.ok()) continue;
            candidates.add(e.getKey());
        }
        if (candidates.isEmpty()) return Map.of();

        int allowed = Math.min(indexPoolMaxLiveFetches, candidates.size());
        if (candidates.size() > allowed) {
            degradations.add((candidates.size() - allowed) + " 只指数 ETF 的日线东财没给到，"
                    + "本轮兜底额度（最多 " + indexPoolMaxLiveFetches + " 只）已用完，"
                    + "这些指数本次没有价格位置——等下一次每日同步把日线预取进库后会自动补上");
        }

        Map<String, MarketDataClient.KlineOutcome> fetched = new LinkedHashMap<>();
        for (String code : candidates.subList(0, allowed)) {
            fetched.put(code, altQuoteSource.fetchTencentKline(code, marketDataClient.klineLimit()));
        }
        Map<String, MarketDataClient.KlineOutcome> toCache = new LinkedHashMap<>();
        for (Map.Entry<String, MarketDataClient.KlineOutcome> e : fetched.entrySet()) {
            MarketDataClient.KlineOutcome outcome = e.getValue();
            if (outcome == null || !outcome.ok()) continue;
            out.put(e.getKey(), outcome);
            toCache.put(secidByCode.get(e.getKey()), outcome);
        }
        if (!toCache.isEmpty()) cache.putKlines(toCache);
        return fetched;
    }

    /**
     * 指数基金块：价格位置与规模来自行情/日线；PE 分位只读项目自己的库。
     *
     * <p><b>这里最要紧的一条是「不留空，也不兜 0」。</b>每个可空数值都配了一个状态串，
     * 规则是 {@code xStatus != null ⟺ 对应数值 == null}（见 {@link ScreenerResultDTO.IndexFundItem}）。
     * 缺值一律走状态串，**绝不**为了填满格子给个 0——0 会被读成「今天没涨没跌」「规模为零」，
     * 那是把一个未知说成了一个已知，正是章程 §6 明令禁止的。
     *
     * <p>状态串只写「为什么缺 + 下一步怎样」，口径类的话（「这是指数的分位，不是 ETF 的」）
     * 走 {@code notes}。两者混在一起会让卡片上最重要的一句警告淹没在缺值说明里。
     *
     * @param klines 日线取数结果，**只为了取 {@code provider}**——卡片上要印「价格位置来自哪里」。
     */
    private List<ScreenerResultDTO.IndexFundItem> buildIndexFunds(
            Map<String, PricePosition> positions,
            Map<String, MarketDataClient.KlineOutcome> klines,
            List<String> degradations,
            List<String> notes) {
        Map<String, MarketDataClient.EtfQuote> quotes = fetchIndexQuotes(degradations, notes);
        ValuationRead valuations = latestValuations(degradations);

        List<ScreenerResultDTO.IndexFundItem> items = new ArrayList<>();
        for (IndexFundPool.Fund f : indexPool.funds()) {
            MarketDataClient.EtfQuote q = f.hasEtf() ? quotes.get(f.etfCode()) : null;
            PricePosition pos = f.hasEtf() ? positions.get(f.etfCode()) : null;
            List<String> cardNotes = new ArrayList<>();

            // ---- 数值先取出来，状态串随后逐格对照着填 ----
            BigDecimal price = q == null ? null : q.price();
            BigDecimal pctChange = q == null ? null : q.pctChange();
            BigDecimal amountYi = yi(q == null ? null : q.amount());
            BigDecimal scaleYi = yi(q == null ? null : q.marketCap());

            boolean hasPosition = pos != null && pos.isAvailable();
            BigDecimal pricePercentile = hasPosition ? pos.getPricePercentile() : null;
            BigDecimal drawdownFromHigh = hasPosition ? pos.getDrawdownFromHigh() : null;
            BigDecimal maxDrawdownInYear = hasPosition ? pos.getMaxDrawdownInYear() : null;
            BigDecimal annualizedVolatility = hasPosition ? pos.getAnnualizedVolatility() : null;
            BigDecimal vsMa250 = hasPosition ? pos.getVsMa250() : null;

            BigDecimal peTtm = null;
            BigDecimal pePercentile = null;
            LocalDate valuationDate = null;
            if (f.hasValuation()) {
                MarketValuationHistory h = valuations.rows().get(f.indexCode());
                if (h != null) {
                    peTtm = h.getPeTtm();
                    pePercentile = h.getPePercentile();
                    valuationDate = h.getTradeDate();
                }
            }

            String noEtf = "该指数没有对应的可交易 ETF，所以取不到行情，也取不到价格位置与规模";

            // ---- 行情四格 ----
            String priceStatus = null;
            String pctChangeStatus = null;
            String amountStatus = null;
            String scaleStatus = null;
            if (!f.hasEtf()) {
                priceStatus = noEtf;
                pctChangeStatus = noEtf;
                amountStatus = noEtf;
                scaleStatus = noEtf;
            } else if (q == null) {
                priceStatus = "东财、腾讯、新浪三个源都没取到该 ETF 的行情，价格未确认；下一次点击会重试";
                pctChangeStatus = "行情未取到，涨跌幅未确认；下一次点击会重试";
                amountStatus = "行情未取到，成交额未确认；下一次点击会重试";
                scaleStatus = "行情未取到，规模未确认；下一次点击会重试";
            } else {
                // 有行情行也不代表每一格都有：兜底源给的字段本来就不全，
                // 腾讯还会在字段位对不上时把涨跌幅/成交额/市值整组判为不可信。
                // 缺在哪儿要说出来，否则那一格就是空白，读者会以为是我们没取到、或者以为那是 0。
                boolean sina = AltQuoteSource.PROVIDER_SINA.equals(q.provider());
                String src = sourceLabel(q.provider());
                if (price == null) priceStatus = src + "这一批没有给出价格；下一次点击会重试";
                if (pctChange == null) {
                    // 页面是纯文本渲染，星号会原样显示出来，所以强调只能用字词，不能用 markdown
                    pctChangeStatus = sina
                            ? "新浪兜底源没有回昨收价，涨跌幅算不出来（这是缺失，不是 0）"
                            : src + "的涨跌幅字段位本次不可信，按缺失处理（这是缺失，不是 0）";
                }
                if (amountYi == null) {
                    amountStatus = src + "本次没有给出成交额（字段位不可信时整组按缺失处理）";
                }
                if (scaleYi == null) {
                    // 这一句是有名的：新浪真的不给规模。为它去翻十几页清单不值，
                    // 但也绝不能拿别的数顶上。
                    scaleStatus = sina
                            ? "新浪兜底源不提供基金规模字段（它只给价格与成交额）"
                            : src + "本次没有给出基金规模";
                }
            }

            // ---- 价格位置五格（同一次日线计算）----
            //
            // 这五格**不是**全有或全无：日线不足 250 根时「对 MA250」是 null，
            // 而其余四格有值。所以状态串按「实际缺了哪几格」来定，
            // 缺一格也只让那一格显红——否则 200 根日线会让整块价格位置看起来全废。
            List<String> missingPosition = new ArrayList<>();
            if (pricePercentile == null) missingPosition.add("一年价格分位");
            if (drawdownFromHigh == null) missingPosition.add("距一年最高点");
            if (maxDrawdownInYear == null) missingPosition.add("一年最大回撤");
            if (annualizedVolatility == null) missingPosition.add("年化波动");
            if (vsMa250 == null) missingPosition.add("对 MA250");

            String positionStatus = null;
            if (!f.hasEtf()) {
                positionStatus = noEtf;
            } else if (!hasPosition) {
                positionStatus = (pos == null
                        ? "未抓取日线，价格位置未确认；下一次点击会重试"
                        : String.join("；", pos.getDegradations()))
                        + "；日线由每日同步预取进库，同步完成后重新点击即可看到";
            } else if (!missingPosition.isEmpty()) {
                // 计算器自己给的措辞最准（「日线不足 250 根，MA250 未确认」），原样用它
                positionStatus = pos.getDegradations().isEmpty()
                        ? "未计算：" + String.join("、", missingPosition)
                        + "（日线样本不足或数据不可用）；每日同步写入更多日线后重新点击即可看到"
                        : String.join("；", pos.getDegradations());
            }

            if (hasPosition) {
                // 值齐全但计算器标了降级的情形：例如「近一年价格无波动区间，价格分位按 50 处理」。
                // 它不是缺值（所以不能进状态串），但它改写了那个数的含义，必须在卡上说出来。
                List<String> leftover = new ArrayList<>(pos.getDegradations());
                if (!missingPosition.isEmpty()) leftover.clear();   // 已原样进状态串，不重复印
                cardNotes.addAll(leftover);

                if (PricePositionCalculator.isStale(pos, LocalDate.now(SHANGHAI))) {
                    // 陈不陈旧由读者看着卡片上印的「日线截止」自己判断，这里只给事实
                    cardNotes.add("日线最后一根为 " + pos.getLastTradeDate()
                            + "，距今已超过 15 个自然日，可能停牌或数据未更新");
                }
            }

            // ---- 估值两格 ----
            String peStatus = null;
            String percentileStatus = null;
            String percentileMethod = f.hasValuation() ? f.percentileMethod() : null;
            if (!f.hasValuation()) {
                peStatus = "该指数未接入 PE 数据源";
                percentileStatus = "该指数未接入 PE 历史分位数据源；本页不对其估值高低作任何判断";
            } else if (valuations.failure() != null) {
                // 「查询没成功」与「库里没这个指数」是两件事，卡片上也要分开说。
                // 详细原因只在口径摘要里出现一次，不重复印 200 遍。
                peStatus = "本次读取本地估值库失败，PE(TTM) 未确认；下一次点击会重试";
                percentileStatus = "本次读取本地估值库失败（不是「该指数没有分位」）；下一次点击会重试";
            } else if (valuations.rows().get(f.indexCode()) == null) {
                peStatus = "库里暂无该指数的 PE(TTM) 记录";
                percentileStatus = "库里暂无该指数的估值分位记录；每日 16:30 之后同步写入，"
                        + "同步完成后重新点击即可看到";
            } else {
                if (peTtm == null) {
                    peStatus = "库里有该指数的分位记录，但 PE(TTM) 这一格是空的";
                }
                if (pePercentile == null) {
                    percentileStatus = "库里有该指数的记录，但分位这一格是空的";
                }
                cardNotes.add("PE 分位是「" + f.tracking() + "」这个指数的口径，"
                        + "不是该 ETF 自身的分位；不同估值来源的 PE 口径不同，不可横向比较");
            }

            MarketDataClient.KlineOutcome kline = f.hasEtf() ? klines.get(f.etfCode()) : null;

            items.add(new ScreenerResultDTO.IndexFundItem(
                    f.indexCode(), f.indexName(), f.category(),
                    f.etfCode(), f.etfName(), f.tracking(),
                    price, pctChange, amountYi, scaleYi,
                    pricePercentile, drawdownFromHigh, maxDrawdownInYear,
                    annualizedVolatility, vsMa250,
                    peTtm, pePercentile,
                    priceStatus, pctChangeStatus, amountStatus, scaleStatus,
                    positionStatus, peStatus, percentileStatus,
                    q == null ? null : sourceLabel(q.provider()),
                    hasPosition && kline != null ? sourceLabel(providerOf(kline)) : null,
                    valuationSourceLabel(f.valuationSource()),
                    percentileMethod, valuationDate,
                    hasPosition ? pos.getBarCount() : null,
                    hasPosition ? pos.getLastTradeDate() : null,
                    cardNotes));
        }
        return items;
    }

    /**
     * 池子里的估值来源标识 → 卡片上印给人看的中文。
     *
     * <p>认不出来的名字**原样给出**：哪天池子里多了一个来源，页面上会直接显出那个生名字，
     * 比换成「未知来源」更有用——后者会让人以为是我们没记录，其实记录一直在池子文件里。
     */
    private static String valuationSourceLabel(String source) {
        if (source == null) return null;
        return switch (source) {
            case IndexFundPool.SOURCE_CSINDEX -> "中证指数官网";
            case IndexFundPool.SOURCE_DANJUAN -> "蛋卷基金";
            default -> source;
        };
    }

    /**
     * 指数池整块的估值分位：**一次批量查询**（缓存命中就不查）。
     *
     * <p>逐条 {@code latest()} 在 7 条池子上无感，到 200 条就是 200 次往返——
     * 同一张卡片上的数没有理由各自单跑一趟。
     *
     * <p><b>读库失败与「库里没有」必须分开说。</b>两者都表现为「这张卡的估值是空的」，
     * 但原因完全不同：前者是这一趟查询没成功（下次可能就有），后者是每日同步还没写到这个指数。
     * 把查询失败也一并写成「库里暂无记录」，等于告诉用户一件不真的事——
     * 章程 §6「缺了就是缺了，要把为什么缺也标出来」说的正是这个。
     */
    private ValuationRead latestValuations(List<String> degradations) {
        List<IndexFundPool.Fund> need = indexPool.funds().stream()
                .filter(IndexFundPool.Fund::hasValuation)
                .toList();
        if (need.isEmpty()) return ValuationRead.EMPTY;

        Optional<Map<String, MarketValuationHistory>> cached = cache.valuations();
        if (cached.isPresent()) return new ValuationRead(cached.get(), null);

        List<MarketValuationHistoryService.Key> keys = need.stream()
                .map(f -> new MarketValuationHistoryService.Key(f.indexCode(), f.percentileMethod()))
                .toList();
        Map<String, MarketValuationHistory> rows;
        try {
            rows = valuationHistoryService.latestForIndices(keys);
        } catch (RuntimeException e) {
            log.warn("批量读估值分位失败：{}", e.toString());
            String reason = MarketDataClient.shortReason(e);
            degradations.add("读取本地估值分位失败（不是「这些指数没有分位」，是这次查询没成功）："
                    + reason + "。本页的 PE 与分位为空，行情与价格位置不受影响");
            // 失败要**带出去**：卡片上那句「库里暂无该指数的记录」在查询失败时是假的，
            // 而它是印给用户看的最显眼的那一句。原因只在这里给全，卡片上用短句。
            return new ValuationRead(Map.of(), reason);
        }
        cache.putValuations(rows);
        return new ValuationRead(rows, null);
    }

    /**
     * 估值读库的结果：**数据 + 「这次读没读成」**。
     *
     * <p>只返回一个 Map 是不够的——空 Map 同时意味着「库里一个都没有」和「这次没读成功」，
     * 而这两件事在卡片上要说不同的话。失败不会被缓存（见 {@link #latestValuations}），
     * 所以下一次点击会再试一次。
     */
    private record ValuationRead(Map<String, MarketValuationHistory> rows, String failure) {
        static final ValuationRead EMPTY = new ValuationRead(Map.of(), null);
    }

    /**
     * 指数行情兜底链：东财 push2 → 腾讯 → 新浪。
     *
     * <p><b>每一层只补上一层缺的那几只，不是整批重打。</b>「7 只里挂了 2 只」只产生 2 只的兜底外呼，
     * 而不是把 7 只再问两遍——兜底链的第一价值是消掉空白，第二价值是别把外呼量乘三。
     *
     * <p>兜底源被限流时**只记它自己**的冷却（见 {@link ScreenerCache}）：腾讯挨的限流
     * 不该顺手把东财的清单请求也锁死，那会让页面上的「东财在限流」变成一句假话。
     *
     * <p>用了哪几个源写进口径摘要——「这个数是哪来的」是全局事实，重复印在每张卡上不会更有用。
     */
    private Map<String, MarketDataClient.EtfQuote> fetchIndexQuotes(
            List<String> degradations, List<String> notes) {
        Map<String, MarketDataClient.EtfQuote> out = new LinkedHashMap<>();
        List<IndexFundPool.Fund> funds = indexPool.withEtf();
        List<String> secids = funds.stream().map(IndexFundPool.Fund::secid).toList();

        out.putAll(marketDataClient.fetchEtfQuotes(secids));

        fetchMissingQuotes(out, missingCodes(funds, out), AltQuoteSource.PROVIDER_TENCENT);
        fetchMissingQuotes(out, missingCodes(funds, out), AltQuoteSource.PROVIDER_SINA);

        long stillMissing = funds.stream()
                .filter(f -> !out.containsKey(f.etfCode())).count();
        if (stillMissing > 0) {
            degradations.add(stillMissing + " 只指数 ETF 的行情三个源都没取到"
                    + "（东财、腾讯、新浪），这些指数的价格与成交额本次为空");
        }
        if (!out.isEmpty()) {
            Map<String, Long> byProvider = new LinkedHashMap<>();
            out.values().forEach(q -> byProvider.merge(
                    q.provider() == null ? "未知来源" : q.provider(), 1L, Long::sum));
            notes.add("指数行情来源：" + describeCountByProvider(byProvider)
                    + "；兜底源只在主源没给到该只时才启用");
        }
        return out;
    }

    /** 这一批里主源还没给到的代码。每层兜底前重算一次，所以只会问真正缺的那几只。 */
    private static List<String> missingCodes(
            List<IndexFundPool.Fund> funds, Map<String, MarketDataClient.EtfQuote> got) {
        List<String> missing = new ArrayList<>();
        for (IndexFundPool.Fund f : funds) {
            if (f.etfCode() != null && !got.containsKey(f.etfCode())) missing.add(f.etfCode());
        }
        return missing;
    }

    /** 补一批缺的行情。被限流时记该源自己的冷却，其余失败只记日志——指数块是可有可无的一块。 */
    private void fetchMissingQuotes(Map<String, MarketDataClient.EtfQuote> out,
                                    List<String> missing, String provider) {
        if (missing.isEmpty() || cache.inCooldown(provider)) return;
        try {
            Map<String, MarketDataClient.EtfQuote> fetched =
                    AltQuoteSource.PROVIDER_SINA.equals(provider)
                            ? altQuoteSource.fetchSinaQuotes(missing)
                            : altQuoteSource.fetchTencentQuotes(missing);
            out.putAll(fetched);
            if (fetched.isEmpty()) log.warn("{} 兜底行情一只都没补回来（缺 {} 只）", provider, missing.size());
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            cache.enterCooldown(provider);
            log.warn("{} 兜底行情限流：{}", provider, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("{} 兜底行情不可用：{}", provider, MarketDataClient.shortReason(e));
        }
    }

    private static String providerOf(MarketDataClient.KlineOutcome outcome) {
        return outcome.provider() == null ? MarketDataClient.PROVIDER_EASTMONEY : outcome.provider();
    }

    private static String describeCountByProvider(Map<String, Long> byProvider) {
        return byProvider.entrySet().stream()
                .map(e -> sourceLabel(e.getKey()) + " " + e.getValue() + " 只")
                .collect(java.util.stream.Collectors.joining("、"));
    }

    /** 源的名字 → 页面上写给人看的叫法。认不出来的名字原样写出来，别假装认得。 */
    private static String sourceLabel(String provider) {
        if (provider == null) return "未知来源";
        return switch (provider) {
            case MarketDataClient.PROVIDER_EASTMONEY -> "东财";
            case MarketDataClient.PROVIDER_LOCAL -> "本地预取库";
            case AltQuoteSource.PROVIDER_TENCENT -> "腾讯（兜底）";
            case AltQuoteSource.PROVIDER_SINA -> "新浪（兜底）";
            default -> provider;
        };
    }

    private static BigDecimal yi(BigDecimal yuan) {
        return yuan == null ? null : yuan.divide(BigDecimal.valueOf(100000000L), 2, RoundingMode.HALF_UP);
    }
}