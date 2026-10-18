package com.ai.daily.screener.v2;

import com.ai.daily.screener.AltQuoteSource;
import com.ai.daily.screener.CodeLookupService;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.screener.MarketDataClient;
import com.ai.daily.screener.MarketDataException;
import com.ai.daily.screener.ScreenerCache;
import com.ai.daily.screener.StockRow;
import com.ai.daily.screener.StockValuationClient;
import com.ai.daily.warehouse.InstrumentCode;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户查询触发的一次「蚂蚁搬家」：把这只标的的行情、日线、PE 历史搬进本地库。
 *
 * <h2>三条纪律</h2>
 *
 * <ol>
 *   <li><b>被限流绝不重试</b>。东财是按 IP 封的，重试只会把封禁推得更深；
 *       限流后只记冷却，这一轮不再发同类请求。</li>
 *   <li><b>只在预算内搬</b>。{@code core} 端点有 3.5 秒预算（{@link WarehouseProperties#getCoreBudgetMs()}），
 *       超预算的部分留给后台 {@code STOCK_DETAIL}/{@code INDEX_DETAIL} 任务，不在请求线程里硬等。</li>
 *   <li><b>落库的口径写死与 Python 一致</b>：日线 {@code adj_type=QFQ}、{@code source=eastmoney-kline}；
 *       估值 {@code source=eastmoney-valueanalysis}。不一致会让同一只票出现两条序列，
 *       而页面上看起来只是「数据变多了」。</li>
 * </ol>
 *
 * <p><b>财务/资金/指数权重不在这里抓</b>：那几类一次要 3–8 个请求，放进请求线程会把
 * 首屏拖到十几秒。这里只登记 {@code md_sync_job}，由 Python warehouse worker 取走。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnDemandSyncService {

    /** 日线与 Python 同一个 source；换名字等于另起一条序列。 */
    static final String PRICE_SOURCE = "eastmoney-kline";

    /** 与 Python {@code automation/warehouse/sync/prices.py} 的 adj_type 一致。 */
    static final String ADJ_QFQ = "QFQ";

    static final String VALUATION_SOURCE = CodeLookupService.SOURCE_VALUEANALYSIS;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /** 行情缓存 60 秒：同一只票连点两下不该发两次请求，也不能拿昨天的价格当现价。 */
    private static final long QUOTE_CACHE_MS = 60_000L;

    private static final String PROVIDER_LOCAL = "local";

    private final MarketDataClient marketDataClient;
    private final AltQuoteSource altQuoteSource;
    private final StockValuationClient stockValuationClient;
    private final WarehouseRepository repository;
    private final ScreenerCache cache;
    private final WarehouseProperties properties;
    private final IndexFundPool indexFundPool;

    @Value("${screener.lookup-kline-limit:2600}")
    private int klineLimit = 2600;

    private final Map<String, CachedQuote> quoteCache = new ConcurrentHashMap<>();

    /** 一次按需同步的报告。{@code quote} 可以为 null——那表示连本地缓存都没有。 */
    public record SyncReport(
            FactsSnapshot.Quote quote,
            DataStatus quoteStatus,
            String quoteNote,
            String name,
            String industry,
            LocalDate listDate,
            DataStatus priceStatus,
            String priceNote,
            DataStatus valuationStatus,
            String valuationNote,
            boolean syncPending,
            long retryAfterMs,
            /** 本轮是否真的从源拿到了新数据（行情/日线/估值任一）。 */
            boolean fetchedFromSource,
            List<String> notes
    ) {
        public boolean hasUsablePrice() {
            return quote != null && quote.price() != null;
        }
    }

    private record CachedQuote(FactsSnapshot.Quote quote, long atMillis) {
    }

    public SyncReport sync(InstrumentCode instrument, String nameHint) {
        long deadline = System.nanoTime() + Math.max(500, properties.getCoreBudgetMs()) * 1_000_000L;
        List<String> notes = new ArrayList<>();
        LocalDate today = LocalDate.now(SHANGHAI);

        boolean syncPending = false;
        boolean fetchedFromSource = false;
        long retryAfterMs = 0;

        // ------------------------------------------------------------
        // 行情
        // ------------------------------------------------------------
        FactsSnapshot.Quote quote = null;
        DataStatus quoteStatus = DataStatus.EMPTY;
        String quoteNote = null;
        boolean eastmoneyAnsweredAbsent = false;
        // 源认出了这只代码、但报价字段不可用（缺价 / 字段漂移）。这与「查无此码」是两回事：
        // 前者要 503 并提示源结构可能变化，后者才是 404「换个代码」。
        boolean eastmoneyRecognized = false;
        // 身份信息只在这轮取到行情时才有。没有就留 null，由调用方保留仓库里的旧值。
        String name = null;
        String industry = null;
        LocalDate listDate = null;

        CachedQuote cached = quoteCache.get(instrument.key());
        if (cached != null && System.currentTimeMillis() - cached.atMillis() < QUOTE_CACHE_MS) {
            quote = cached.quote();
            quoteStatus = DataStatus.OK;
            quoteNote = "行情来自 60 秒内缓存";
        }

        if (quote == null && properties.isOnDemandEnabled()) {
            String secid = MarketCodes.secid(instrument);
            if (secid != null && !cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)) {
                try {
                    String raw = MarketCodes.stripExchange(instrument.rawCode());
                    StockRow row = marketDataClient.fetchQuotes(List.of(secid)).get(raw);
                    eastmoneyRecognized = row != null;
                    if (row != null && row.getPrice() != null) {
                        quote = fromStockRow(row, MarketDataClient.PROVIDER_EASTMONEY);
                        quoteStatus = DataStatus.OK;
                        fetchedFromSource = true;
                        name = row.getName();
                        industry = row.getIndustry();
                        listDate = row.getListDate();
                        quoteCache.put(instrument.key(), new CachedQuote(quote, System.currentTimeMillis()));
                    } else if (row != null) {
                        name = row.getName();
                        industry = row.getIndustry();
                        listDate = row.getListDate();
                        quoteNote = "行情源返回了这一只，但没有现价（可能停牌）";
                    } else {
                        eastmoneyAnsweredAbsent = true;
                        quoteNote = "东财行情里没有这只标的";
                    }
                } catch (MarketDataException.MarketDataRateLimitedException e) {
                    cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
                    retryAfterMs = cooldownMs(MarketDataClient.PROVIDER_EASTMONEY);
                    quoteNote = "东财行情限流，冷却中不再外呼";
                    notes.add(quoteNote);
                } catch (RuntimeException e) {
                    quoteNote = "东财行情不可用（" + MarketDataClient.shortReason(e) + "）";
                    notes.add(quoteNote);
                }
            } else if (secid == null) {
                quoteNote = "这个代码在市场行情接口里没有稳定形态";
            } else {
                retryAfterMs = Math.max(retryAfterMs, cooldownMs(MarketDataClient.PROVIDER_EASTMONEY));
                quoteNote = "东财行情冷却中，本次未外呼";
            }

            // 兜底源。腾讯与新浪是独立配额，东财被拒时它们很可能还能用。
            // 这里**只在东财明确没给到（限流 / 不可达 / 没这一行）时**往下走，且绝不重试东财。
            String symbol = MarketCodes.quoteSymbol(instrument);
            boolean fallbackAnsweredAbsent = false;
            if (quote == null && symbol != null) {
                FallbackQuote tencent = tryFallbackQuote(instrument, symbol,
                        AltQuoteSource.PROVIDER_TENCENT, notes);
                FallbackQuote sina = tencent.quote() == null
                        ? tryFallbackQuote(instrument, symbol, AltQuoteSource.PROVIDER_SINA, notes)
                        : FallbackQuote.NONE;
                quote = tencent.quote() != null ? tencent.quote() : sina.quote();
                name = tencent.quote() != null ? tencent.name() : sina.name();
                fallbackAnsweredAbsent = tencent.answeredAbsent() || sina.answeredAbsent();
                if (quote != null) {
                    quoteStatus = DataStatus.OK;
                    fetchedFromSource = true;
                    quoteNote = "行情取自" + providerLabel(quote.provider()) + "（东财没给到）";
                    quoteCache.put(instrument.key(), new CachedQuote(quote, System.currentTimeMillis()));
                }
            }
            eastmoneyAnsweredAbsent = eastmoneyAnsweredAbsent || fallbackAnsweredAbsent;
        } else if (quote == null) {
            quoteNote = "按需取数已关闭，只读本地仓库";
        }

        // 本地最近收盘价兜底。它是真实数据，但**不是现价**——所以状态是 PARTIAL，
        // 页面必须能看出这一格来自仓库、是哪一天的。
        if (quote == null) {
            String sourceNote = quoteNote;
            quote = localQuote(instrument);
            if (quote != null) {
                quoteStatus = DataStatus.PARTIAL;
                // 保留上一次失败的原因（限流/不可达/字段缺失）。只写「没有返回报价」
                // 会把「东财正在冷却」这类可操作信息吞掉，页面看起来像源毫无原因地沉默了。
                String fallback = "使用本地最近收盘价（" + quote.asOf() + "）";
                quoteNote = sourceNote == null || sourceNote.isBlank()
                        ? "行情源本次没有返回报价，" + fallback
                        : sourceNote + "；" + fallback;
            } else {
                quoteStatus = DataStatus.FAILED;
                quoteNote = quoteNote == null ? "没有任何可用行情" : quoteNote;
            }
        }

        // ------------------------------------------------------------
        // 日线
        // ------------------------------------------------------------
        DataStatus priceStatus;
        String priceNote;
        String priceKey = instrument.key();
        String latestDate = repository.latestPriceDate(priceKey);
        long priceCount = repository.countPrices(priceKey);
        boolean fresh = isFresh(latestDate, properties.getPriceStaleTradingDays(), today);
        boolean priceFetched = false;

        if (priceCount > 0 && fresh) {
            priceStatus = priceCount >= 250 ? DataStatus.OK : DataStatus.PARTIAL;
            priceNote = priceCount >= 250 ? null : "本地只有 " + priceCount + " 根日线，趋势指标不完整";
        } else {
            String secid = MarketCodes.secid(instrument);
            if (!properties.isOnDemandEnabled()) {
                priceStatus = priceCount > 0 ? DataStatus.PARTIAL : DataStatus.EMPTY;
                priceNote = priceCount > 0
                        ? "按需取数已关闭，本地日线未更新（最新 " + latestDate + "）"
                        : "按需取数已关闭，本地没有日线";
            } else if (secid == null) {
                priceStatus = priceCount > 0 ? DataStatus.PARTIAL : DataStatus.EMPTY;
                priceNote = "这个代码没有可用的日线接口形态";
            } else if (cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)) {
                priceStatus = priceCount > 0 ? DataStatus.PARTIAL : DataStatus.FAILED;
                priceNote = priceCount > 0
                        ? "东财冷却中，日线未更新（最新 " + latestDate + "）"
                        : "东财冷却中，本轮的日线没有取到";
                retryAfterMs = Math.max(retryAfterMs, cooldownMs(MarketDataClient.PROVIDER_EASTMONEY));
            } else if (System.nanoTime() >= deadline) {
                priceStatus = priceCount > 0 ? DataStatus.PARTIAL : DataStatus.SYNCING;
                priceNote = "本次查询预算已用完，日线留到下一次更新";
                syncPending = true;
            } else {
                MarketDataClient.KlineRowsOutcome outcome =
                        marketDataClient.fetchKlineRows(secid, klineLimit);
                if (outcome.ok()) {
                    persistPrices(priceKey, outcome.rows());
                    priceFetched = true;
                    fetchedFromSource = true;
                    priceCount = outcome.rows().size();
                    priceStatus = priceCount >= 250 ? DataStatus.OK : DataStatus.PARTIAL;
                    priceNote = priceCount >= 250 ? null : "拿到 " + priceCount + " 根日线，不足 250 根";
                } else {
                    if (outcome.throttled()) {
                        cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
                        retryAfterMs = Math.max(retryAfterMs,
                                cooldownMs(MarketDataClient.PROVIDER_EASTMONEY));
                    }
                    priceStatus = priceCount > 0 ? DataStatus.PARTIAL : DataStatus.FAILED;
                    priceNote = priceCount > 0
                            ? outcome.failureReason() + "，沿用本地日线（最新 " + latestDate + "）"
                            : outcome.failureReason();
                    notes.add(priceNote);
                }
            }
        }
        if (priceStatus == DataStatus.FAILED || priceStatus == DataStatus.SYNCING) {
            syncPending = true;
        }

        // ------------------------------------------------------------
        // 个股 PE/PB 历史
        // ------------------------------------------------------------
        DataStatus valuationStatus = DataStatus.NOT_APPLICABLE;
        String valuationNote = null;
        if (instrument.isStock()) {
            String raw = instrument.rawCode();
            Optional<WarehouseRows.ValuationPoint> latest =
                    repository.latestValuation(priceKey, VALUATION_SOURCE);
            boolean valuationFresh = latest
                    .map(v -> v.tradeDate() != null
                            && !v.tradeDate().isBefore(today.minusDays(7)))
                    .orElse(false);

            if (valuationFresh) {
                valuationStatus = DataStatus.OK;
            } else if (!properties.isOnDemandEnabled()) {
                valuationStatus = latest.isPresent() ? DataStatus.PARTIAL : DataStatus.EMPTY;
                valuationNote = "按需取数已关闭，估值历史未更新";
            } else if (cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)) {
                valuationStatus = latest.isPresent() ? DataStatus.PARTIAL : DataStatus.FAILED;
                valuationNote = "东财冷却中，估值历史未更新";
                retryAfterMs = Math.max(retryAfterMs, cooldownMs(MarketDataClient.PROVIDER_EASTMONEY));
            } else if (System.nanoTime() >= deadline) {
                valuationStatus = latest.isPresent() ? DataStatus.PARTIAL : DataStatus.SYNCING;
                valuationNote = "本次查询预算已用完，估值历史留到下一次更新";
                syncPending = true;
            } else {
                try {
                    StockValuationClient.History history = stockValuationClient.fetchHistory(raw);
                    if (history.ok() && !history.points().isEmpty()) {
                        persistValuations(priceKey, history.points());
                        valuationStatus = DataStatus.OK;
                        fetchedFromSource = true;
                        if (history.daysWithoutPe() > 0) {
                            valuationNote = history.daysWithoutPe() + " 个交易日没有 PE（亏损或未披露），已排除在分位之外";
                        }
                    } else {
                        valuationStatus = latest.isPresent() ? DataStatus.PARTIAL : DataStatus.EMPTY;
                        valuationNote = history.failureReason() == null
                                ? "估值分析源没有这只票的历史" : history.failureReason();
                    }
                } catch (MarketDataException.MarketDataRateLimitedException e) {
                    cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
                    retryAfterMs = Math.max(retryAfterMs, cooldownMs(MarketDataClient.PROVIDER_EASTMONEY));
                    valuationStatus = latest.isPresent() ? DataStatus.PARTIAL : DataStatus.FAILED;
                    valuationNote = "东财估值分析限流，冷却中不再外呼";
                    notes.add(valuationNote);
                } catch (RuntimeException e) {
                    valuationStatus = latest.isPresent() ? DataStatus.PARTIAL : DataStatus.FAILED;
                    valuationNote = "估值历史不可用（" + MarketDataClient.shortReason(e) + "）";
                    notes.add(valuationNote);
                }
            }
        }

        // ------------------------------------------------------------
        // 后台任务：财务/资金、指数权重
        // ------------------------------------------------------------
        if (instrument.isStock()) {
            boolean financialsMissing = repository
                    .financialsAvailableAsOf(instrument.rawCode(), today, 1).isEmpty();
            boolean moneyflowMissing = repository.moneyflow(instrument.rawCode(), 1).isEmpty();
            if (financialsMissing || moneyflowMissing) {
                syncPending = true;
                if (properties.isOnDemandEnabled()) {
                    enqueueStockDetail(instrument, nameHint, today);
                }
            }
        } else {
            String indexCode = indexCodeOf(instrument);
            if (indexCode != null) {
                boolean profileMissing = repository.latestIndexProfile(indexCode).isEmpty();
                boolean weightsMissing = repository.latestIndustryWeights(indexCode).isEmpty();
                if (profileMissing || weightsMissing) {
                    syncPending = true;
                    if (properties.isOnDemandEnabled()) {
                        enqueueIndexDetail(indexCode, today);
                    }
                }
            }
        }

        boolean hasAnyPrice = priceFetched || hasLocalPrice(priceKey);
        if (!quoteStatus.usable() && !hasAnyPrice) {
            // 既没有现价、也没有本地日线：这一轮确实什么都给不出来。
            if (retryAfterMs > 0) {
                throw new MarketDataException.MarketDataRateLimitedException(
                        "行情源限流冷却中，约 " + Math.max(1, retryAfterMs / 60_000) + " 分钟后可再试"
                                + "（冷却期内不再外呼，免得把封禁推得更深）");
            }
            if (eastmoneyRecognized) {
                // 源认得这个代码，只是没有可用的报价字段。**不能回 404**：
                // 那会让人去改一个本来就对的代码，而真实原因是源结构变了或该标的停牌。
                throw new MarketDataException("行情源返回了 " + instrument.rawCode()
                        + "，但没有可用报价（可能停牌或源字段变化），本次未给出结论");
            }
            if (eastmoneyAnsweredAbsent) {
                throw new InstrumentNotFoundException("行情源查不到代码 " + instrument.rawCode()
                        + "，请确认代码是否正确");
            }
            throw new MarketDataException("行情源本次没有返回 "
                    + instrument.rawCode() + " 的报价，本次未查询");
        }

        return new SyncReport(quote, quoteStatus, quoteNote, name, industry, listDate,
                priceStatus, priceNote, valuationStatus, valuationNote,
                syncPending, retryAfterMs, fetchedFromSource, List.copyOf(notes));
    }

    // ------------------------------------------------------------------
    // 行情
    // ------------------------------------------------------------------

    /**
     * 兜底行情的一次尝试。
     *
     * @param quote          拿到的行情；没有就是 null
     * @param answeredAbsent 源**确实发过请求**且明确没有这一行。冷却中/不可达都算「没问」，
     *                       不能与「问了没有」混在一起——后者才能回 404
     */
    private record FallbackQuote(FactsSnapshot.Quote quote, boolean answeredAbsent, String name) {
        static final FallbackQuote NONE = new FallbackQuote(null, false, null);
    }

    private FallbackQuote tryFallbackQuote(InstrumentCode instrument, String symbol,
                                           String provider, List<String> notes) {
        if (cache.inCooldown(provider)) {
            return FallbackQuote.NONE;
        }
        try {
            Map<String, MarketDataClient.EtfQuote> got = AltQuoteSource.PROVIDER_SINA.equals(provider)
                    ? altQuoteSource.fetchSinaQuotesBySymbols(List.of(symbol))
                    : altQuoteSource.fetchTencentQuotesBySymbols(List.of(symbol));
            MarketDataClient.EtfQuote q = got.get(MarketCodes.stripExchange(instrument.rawCode()));
            if (q == null) {
                return new FallbackQuote(null, true, null);
            }
            if (q.price() == null || q.price().signum() <= 0) {
                return FallbackQuote.NONE;
            }
            return new FallbackQuote(new FactsSnapshot.Quote(provider, providerLabel(provider),
                    q.price(), q.pctChange(), q.amount(), null, q.marketCap(), q.peTtm(), q.pb(),
                    null, today()), false, q.name());
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            cache.enterCooldown(provider);
            notes.add(providerLabel(provider) + "行情限流，转下一个源");
            return FallbackQuote.NONE;
        } catch (RuntimeException e) {
            notes.add(providerLabel(provider) + "行情不可用（" + MarketDataClient.shortReason(e) + "）");
            return FallbackQuote.NONE;
        }
    }

    private FactsSnapshot.Quote fromStockRow(StockRow row, String provider) {
        return new FactsSnapshot.Quote(provider, providerLabel(provider), row.getPrice(),
                row.getPctChange(), row.getAmount(), row.getTurnoverRate(), row.getTotalMarketCap(),
                row.getPeTtm(), row.getPb(), row.getDividendYield(), today());
    }

    /**
     * 本地最近收盘价拼出来的「行情」。
     *
     * <p>{@code asOf} 取自最后一根日线，不是今天：页面据此显示「数据日期」，
     * 拿今天去填会把一根三天前的收盘价说成现价。
     */
    private FactsSnapshot.Quote localQuote(InstrumentCode instrument) {
        Optional<WarehouseRows.PriceBar> price = repository.latestPrice(instrument.key(), ADJ_QFQ);
        if (price.isEmpty()) {
            return null;
        }
        WarehouseRows.PriceBar bar = price.get();
        BigDecimal pe = null;
        BigDecimal pb = null;
        BigDecimal dividendYield = null;
        if (instrument.isStock()) {
            Optional<WarehouseRows.ValuationPoint> v =
                    repository.latestValuation(instrument.key(), VALUATION_SOURCE);
            if (v.isPresent()) {
                pe = v.get().peTtm();
                pb = v.get().pbMrq();
                dividendYield = v.get().dividendYield();
            }
        }
        return new FactsSnapshot.Quote(PROVIDER_LOCAL, providerLabel(PROVIDER_LOCAL),
                bar.closePrice(), bar.pctChange(), bar.amount(), bar.turnoverRate(), null,
                pe, pb, dividendYield, bar.tradeDate());
    }

    // ------------------------------------------------------------------
    // 落库
    // ------------------------------------------------------------------

    private void persistPrices(String instrumentCode, List<MarketDataClient.KlineRow> rows) {
        LocalDateTime fetchedAt = LocalDateTime.now(SHANGHAI);
        List<WarehouseRows.PriceBar> bars = new ArrayList<>(rows.size());
        for (MarketDataClient.KlineRow row : rows) {
            bars.add(new WarehouseRows.PriceBar(instrumentCode, row.tradeDate(), ADJ_QFQ,
                    row.openPrice(), row.highPrice(), row.lowPrice(), row.closePrice(),
                    row.volume(), row.amount(), row.turnoverRate(), row.pctChange(),
                    PRICE_SOURCE, fetchedAt));
        }
        repository.upsertPrices(bars);
        log.info("按需同步：写入 {} 根日线 instrument={} source={}", bars.size(), instrumentCode, PRICE_SOURCE);
    }

    private void persistValuations(String instrumentCode, List<StockValuationClient.Point> points) {
        LocalDateTime fetchedAt = LocalDateTime.now(SHANGHAI);
        List<WarehouseRows.ValuationPoint> rows = new ArrayList<>(points.size());
        for (StockValuationClient.Point p : points) {
            if (p.tradeDate() == null) continue;
            if (p.peTtm() == null && p.pbMrq() == null && p.closePrice() == null) continue;
            rows.add(new WarehouseRows.ValuationPoint(instrumentCode, p.tradeDate(), p.peTtm(),
                    p.pbMrq(), null, null, p.closePrice(), null, VALUATION_SOURCE, fetchedAt));
        }
        repository.upsertValuations(rows);
        log.info("按需同步：写入 {} 条估值 instrument={}", rows.size(), instrumentCode);
    }

    // ------------------------------------------------------------------
    // 后台任务
    // ------------------------------------------------------------------

    private void enqueueStockDetail(InstrumentCode instrument, String nameHint, LocalDate today) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stock_code", instrument.rawCode());
        if (nameHint != null && !nameHint.isBlank()) {
            payload.put("name", nameHint);
        }
        String jobKey = "STOCK_DETAIL:" + instrument.rawCode() + ":" + today;
        repository.enqueueJob(jobKey, "STOCK_DETAIL", 100, instrument.key(), json(payload));
    }

    private void enqueueIndexDetail(String indexCode, LocalDate today) {
        IndexFundPool.Fund fund = indexFundPool.byIndexCode(indexCode);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("index_code", indexCode);
        if (fund != null) {
            if (fund.csindexCode() != null) payload.put("csindex_code", fund.csindexCode());
            if (fund.danjuanCode() != null) payload.put("danjuan_code", fund.danjuanCode());
        }
        String jobKey = "INDEX_DETAIL:" + indexCode + ":" + today;
        repository.enqueueJob(jobKey, "INDEX_DETAIL", 80, "INDEX:" + indexCode, json(payload));
    }

    /** 极小的 JSON 拼装：payload 里只有代码和名称，引号/反斜杠必须转义。 */
    static String json(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (e.getValue() == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":\"")
                    .append(escape(String.valueOf(e.getValue()))).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private boolean hasLocalPrice(String instrumentCode) {
        return repository.latestPrice(instrumentCode, ADJ_QFQ).isPresent();
    }

    private long cooldownMs(String provider) {
        long minutes = cache.cooldownRemainingMinutes(provider);
        return Math.max(0, minutes) * 60_000L;
    }

    private String indexCodeOf(InstrumentCode instrument) {
        if (instrument.isIndex()) {
            return instrument.rawCode();
        }
        IndexFundPool.Fund fund = indexFundPool.byEtfCode(instrument.rawCode());
        return fund == null ? null : fund.indexCode();
    }

    private static LocalDate today() {
        return LocalDate.now(SHANGHAI);
    }

    static String providerLabel(String provider) {
        if (provider == null) return "未知来源";
        return switch (provider) {
            case MarketDataClient.PROVIDER_EASTMONEY -> "东财";
            case AltQuoteSource.PROVIDER_TENCENT -> "腾讯";
            case AltQuoteSource.PROVIDER_SINA -> "新浪";
            case PROVIDER_LOCAL -> "本地仓库";
            default -> provider;
        };
    }

    /**
     * 日线是否还算新鲜。
     *
     * <p>按**工作日**算，不按自然日：周五的数据在周一开盘前仍然是最新交易日，
     * 按自然日算会把它判成「三天没更新」，每个周一早上都白抓一次。
     *
     * @param latest  最后一根日线的日期（ISO 字符串，可能为 null）
     * @param maxDays 允许落后几个交易日
     */
    static boolean isFresh(String latest, int maxDays, LocalDate today) {
        if (latest == null || latest.isBlank() || today == null) {
            return false;
        }
        try {
            return tradingDaysBetween(LocalDate.parse(latest), today) <= Math.max(0, maxDays);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 严格晚于 {@code from}、且不晚于 {@code to} 的工作日数。 */
    static int tradingDaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return 0;
        }
        int days = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        return days;
    }
}
