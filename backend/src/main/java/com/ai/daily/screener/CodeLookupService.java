package com.ai.daily.screener;

import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 「代码查询」：输入一个代码，实时给出价格、PE、PE分位（八档，含五年/十年）。
 *
 * <p><b>按需、不落库、不推送。</b>这是它和 ETF 日报（每日 18:00 生成）以及低估精选
 * （管理员手工触发、结果落一行历史）最根本的区别：查完即走，进程内缓存
 * {@value #CACHE_TTL_SECONDS} 秒只为让连点不重复外呼。
 *
 * <p><b>一条铁律：缺数据不许兜底成 0 或破折号。</b>每一处取不到的地方都要说得清
 * 是哪一种取不到——「源不提供历史」「历史不够长」「当日附近没有观测」「源不可达」
 * 是四件不同的事，下一步该做什么也完全不同（章程 §6）。所以这个类里没有任何
 * {@code orElse(ZERO)}，所有缺失都走
 * {@link CodeLookupDTO.LookbackCell#status()}。
 *
 * <p><b>判类型靠码段表，不靠「哪个源先回」。</b>6 位数字里既有 A 股也有中证指数
 * （000905 既是深市的厦门港务、也是中证500），让「谁先回谁赢」决定用哪个，
 * 会出现「同一串数字今天是一只票、明天是一个指数」——而页面上只看得到一个名字。
 * 判据必须是**输入的函数**，所以写成 {@link #marketOf}。
 *
 * <p>不抛业务异常的地方一律把原因写进 {@code status}/{@code notes}；
 * 只有三件事会抛给 controller：格式非法（400）、查不到（404）、被限流（429）。
 */
@Slf4j
@Service
public class CodeLookupService {

    /** 结果在进程内留多久。够让连点与「返回上一页」免费，短到陈旧不至于误导。 */
    public static final int CACHE_TTL_SECONDS = 300;

    /**
     * 往回多取这么久的观测。<b>不是凑整</b>：八档里最远的一档是「十年前当日」，
     * 而要取到那一天的值必须让序列覆盖到它——只取到「十年前的那一天」时，
     * 边界上那一天会因为 {@link LookbackCalculator#STALE_DAYS} 之外的零碎差异而落空。
     */
    static final int HISTORY_MARGIN_DAYS = 30;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    /** 代码串的整体形态。挡住超长串与奇怪字符：它们会被拼进 URL。 */
    private static final Pattern CODE_SHAPE = Pattern.compile("[0-9A-Za-z]{1,12}");

    // ------------------------------------------------------------------
    // 码段表
    // ------------------------------------------------------------------

    /** 沪市股票：主板 600/601/603/605、科创板 688/689、B股 900。 */
    static final Set<String> SH_STOCK = Set.of("600", "601", "603", "605", "688", "689", "900");

    /** 深市股票：主板 000/001、中小板 002/003、创业板 300/301/302。 */
    static final Set<String> SZ_STOCK = Set.of("000", "001", "002", "003", "300", "301", "302");

    static final String LABEL_CSINDEX_SOURCE = "中证官网（中证口径）";
    static final String LABEL_DANJUAN_SOURCE = "蛋卷（蛋卷口径）";
    static final String LABEL_VALUEANALYSIS_SOURCE = "东财估值分析";

    /** 蛋卷只给当日值。这句话用在**该源覆盖不到的档位**上，不能挪作他用。 */
    static final String DANJUAN_NO_HISTORY =
            "该指数口径源（蛋卷）只提供当日值，没有历史序列，这一档分位算不出来";

    public static final String SOURCE_CSINDEX = IndexFundPool.SOURCE_CSINDEX;
    public static final String SOURCE_DANJUAN = IndexFundPool.SOURCE_DANJUAN;
    public static final String SOURCE_VALUEANALYSIS = "eastmoney-valueanalysis";

    private final MarketDataClient marketDataClient;
    private final AltQuoteSource altQuoteSource;
    private final StockValuationClient stockValuationClient;
    private final OffPoolIndexClient offPoolIndexClient;
    private final IndexFundPool indexPool;
    private final MarketValuationHistoryService valuationService;
    private final ScreenerCache cache;

    private final int lookupKlineLimit;

    /** code → 结果。只放成功的那些，见 {@link #lookup}。 */
    private final Map<String, Cached> results = new ConcurrentHashMap<>();

    private record Cached(CodeLookupDTO dto, LocalDateTime at) {}

    public CodeLookupService(
            MarketDataClient marketDataClient,
            AltQuoteSource altQuoteSource,
            StockValuationClient stockValuationClient,
            OffPoolIndexClient offPoolIndexClient,
            IndexFundPool indexPool,
            MarketValuationHistoryService valuationService,
            ScreenerCache cache,
            @Value("${screener.lookup-kline-limit:2600}") int lookupKlineLimit) {
        this.marketDataClient = marketDataClient;
        this.altQuoteSource = altQuoteSource;
        this.stockValuationClient = stockValuationClient;
        this.offPoolIndexClient = offPoolIndexClient;
        this.indexPool = indexPool;
        this.valuationService = valuationService;
        this.cache = cache;
        this.lookupKlineLimit = Math.max(1, lookupKlineLimit);
    }

    /** 查不到的代码。与「取数失败」分开：前者要换代码，后者要等一会儿再试。 */
    public static class CodeNotFoundException extends RuntimeException {
        public CodeNotFoundException(String message) {
            super(message);
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    public CodeLookupDTO lookup(String rawCode) {
        String code = normalise(rawCode);

        // 冷却期内**连请求都不发**。被限流时每一次重试都在把这个 IP 往更深的封禁里推，
        // 所以这里不是「快速失败」的性能优化，是纪律。
        if (cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)) {
            long left = cache.cooldownRemainingMinutes(MarketDataClient.PROVIDER_EASTMONEY);
            throw new MarketDataException.MarketDataRateLimitedException(
                    "行情源限流冷却中，约 " + left + " 分钟后可再试"
                            + "（冷却期内不再外呼，免得把封禁推得更深）");
        }

        CodeLookupDTO cached = cachedResult(code);
        if (cached != null) {
            return cached;
        }

        Outcome outcome = doLookup(code);
        if (!outcome.degraded()) {
            // 只缓存**没有降级**的结果。把一次「中证官网不可达」缓存 5 分钟，
            // 用户点「再试一次」还是同一句话，看起来像功能坏了——
            // 而实际上源早就恢复了。
            store(code, outcome.dto());
        }
        return outcome.dto();
    }

    /**
     * 格式校验。<b>在这里就把超长串和奇怪字符挡掉</b>：代码会被拼进各个源的查询串，
     * 不校验等于把查询串交给调用方拼。
     */
    static String normalise(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("请输入要查询的代码");
        }
        if (!CODE_SHAPE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "代码只能由 1-12 位字母或数字组成（如 510300、000300、300274、NDX），收到的是：" + code);
        }
        return code;
    }

    private record Outcome(CodeLookupDTO dto, boolean degraded) {}

    /**
     * 记一次降级。**两件事绑在一起做，这是有意的**：
     * <ul>
     *   <li>写进 {@code degradations} → 这条结果不进缓存（源恢复了就该立刻反映出来，见 {@link #lookup}）；</li>
     *   <li>写进 {@code notes} → 让用户看见。</li>
     * </ul>
     *
     * <p>只记进 {@code degradations} 是个很容易犯的错：页面上的数字换了源、或者干脆空了，
     * 而解释只存在于服务端的一个集合里——那正是「看着正常、数却不对」最难查的一种。
     * 凡是降级，都值一句话给用户看。
     */
    private static void degrade(List<String> degradations, List<String> notes, String message) {
        degradations.add(message);
        notes.add(message);
    }

    // ==================================================================
    // 判类型
    // ==================================================================

    /** 一次查询走哪条取数路径。{@code kind} 决定后面所有分支。 */
    private record Resolved(CodeLookupDTO.Kind kind, IndexFundPool.Fund fund,
                            OffPoolIndexResolver.Route route, String resolvedCode, String secid) {}

    /** 6 位数字代码落在哪个市场。返回 null = 它不在任何 A 股码段里（可能是个中证指数）。 */
    record Market(int secidPrefix, CodeLookupDTO.Kind kind, String label) {}

    static final Market MARKET_SH_STOCK = new Market(1, CodeLookupDTO.Kind.STOCK, "沪市股票");
    static final Market MARKET_SZ_STOCK = new Market(0, CodeLookupDTO.Kind.STOCK, "深市股票");
    static final Market MARKET_BJ_STOCK = new Market(0, CodeLookupDTO.Kind.STOCK, "北交所股票");
    static final Market MARKET_SH_FUND = new Market(1, CodeLookupDTO.Kind.FUND, "沪市基金");
    static final Market MARKET_SZ_FUND = new Market(0, CodeLookupDTO.Kind.FUND, "深市基金");

    /**
     * 6 位数字代码判市场。
     *
     * <p><b>判不出来的返回 null，不猜一个前缀。</b>拼错了前景很糟：{@code 0.000300}
     * 在深市是一只与沪深300 毫无关系的票，而页面上只会显示一个看着合理的名字。
     * 返回 null 的走池外指数那条路（{@code 930740}、{@code 931xxx} 这类是中证指数的码段）。
     *
     * <p>已知的重叠：{@code 000xxx} 既是深市主板股票，也是中证宽基指数的码段
     * （{@code 000905} 既是厦门港务也是中证500）。<b>池内的那些先被池子拦下</b>
     * （{@code 000905} 在池子里 → 按中证500 处理）；池外的 {@code 000xxx} 按深市股票处理，
     * 拿不到行情时再按池外指数试一次（见 {@link #doLookup}）。
     */
    static Market marketOf(String code) {
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            return null;
        }
        // '5' 开头是沪市基金（510300/588000/511990…）；深市基金是 15x/16x/18x。
        // 这两条要在股票码段之前判：15xxxx 与 1 开头的股票码不冲突，但顺序写清楚更省心。
        if (code.startsWith("5")) return MARKET_SH_FUND;
        if (code.startsWith("15") || code.startsWith("16") || code.startsWith("18")) return MARKET_SZ_FUND;
        // 北交所：43xxxx/83xxxx/87xxxx/88xxxx。东财把它归在 0 号市场。
        if (code.startsWith("4") || code.startsWith("8")) return MARKET_BJ_STOCK;
        String p3 = code.substring(0, 3);
        if (SH_STOCK.contains(p3)) return MARKET_SH_STOCK;
        if (SZ_STOCK.contains(p3)) return MARKET_SZ_STOCK;
        return null;
    }

    private Resolved resolve(String code) {
        // 1) 池内指数的代表 ETF（510300 → 沪深300）。**必须最先判**：
        //    它同时是合法的 6 位码，再往后走就会被当成「池外沪市基金」。
        IndexFundPool.Fund byEtf = indexPool.byEtfCode(code);
        if (byEtf != null) {
            return new Resolved(CodeLookupDTO.Kind.INDEX_FUND, byEtf, null,
                    byEtf.indexCode(), byEtf.secid());
        }
        // 2) 池内指数的裸代码：000300 / SH000300 / SZ399006 / H30533 / NDX。
        IndexFundPool.Fund byIndex = poolIndex(code);
        if (byIndex != null) {
            return new Resolved(CodeLookupDTO.Kind.INDEX, byIndex, null,
                    byIndex.indexCode(), byIndex.secid());
        }
        // 3) 不是 6 位纯数字 → 池外指数，按形态定源（NDX → 蛋卷；H30533 → 中证官网）。
        if (!SIX_DIGITS.matcher(code).matches()) {
            return offPoolResolved(code);
        }
        // 4) 6 位数字但不在任何 A 股码段 → 中证指数的码段（930740、931xxx…）。
        Market market = marketOf(code);
        if (market == null) {
            return offPoolResolved(code);
        }
        return new Resolved(market.kind(), null, null, code, market.secidPrefix() + "." + code);
    }

    /** 池内指数的几种写法都试一遍。顺序无关紧要，命中即止。 */
    private IndexFundPool.Fund poolIndex(String code) {
        for (String key : List.of(code, "SH" + code, "SZ" + code)) {
            IndexFundPool.Fund f = indexPool.byIndexCode(key);
            if (f != null) return f;
        }
        return indexPool.byCsindexCode(code);
    }

    private Resolved offPoolResolved(String code) {
        OffPoolIndexResolver.Route route = OffPoolIndexResolver.resolve(code)
                .orElseThrow(() -> new CodeNotFoundException(notFoundMessage(code)));
        return new Resolved(CodeLookupDTO.Kind.OFF_POOL_INDEX, null, route, route.sourceCode(), null);
    }

    private static String notFoundMessage(String code) {
        return "查不到代码 " + code + "。可接受的写法：池内指数的 ETF 代码（510300）、"
                + "指数代码（000300、SZ399006、NDX）、6 位 A 股代码（300274），"
                + "或中证官网的指数代码（6 位字母数字，如 930740）。";
    }

    // ==================================================================
    // 取数
    // ==================================================================

    private Outcome doLookup(String code) {
        List<String> degradations = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Resolved resolved = resolve(code);
        notes.add("判为" + kindLabel(resolved) + "，实际查询代码 " + resolved.resolvedCode());

        QuoteRef quote = null;
        if (resolved.secid() != null) {
            quote = fetchQuote(resolved, degradations, notes);
            if (quote == null && (resolved.kind() == CodeLookupDTO.Kind.STOCK
                    || resolved.kind() == CodeLookupDTO.Kind.FUND)) {
                // 东财**明确回了**「没有这只标的」（请求成功、没有这一行）。这时才考虑
                // 它是池外的中证指数——000xxx 既是深市股票码段也是中证宽基指数的码段。
                //
                // 关键区别：**取数失败不走这条路**（fetchQuote 那时会抛 503）。
                // 否则东财一挂，所有 6 位代码都会被读成指数——换个时间点结果不同，
                // 而页面上看不出来。
                degenerateToOffPool(code, notes);
                resolved = offPoolResolved(code);
                degrade(degradations, notes,
                        "代码 " + code + " 在行情源里没有这只标的，已按池外指数解释");
            }
        }

        // 估值先问。**不存在的代码要在这一步就现形**：先取日线的话，一个查不到的代码
        // 会被拼成 secid 白打一次 东财（999999 → 1.999999），而那次外呼恰恰是这个
        // 出口 IP 最该省着用的东西。估值源答「没有这个代码」比日线更早也更确定。
        CodeLookupDTO.ValuationView valuation = valuation(resolved, degradations, notes);

        List<PricePositionCalculator.Bar> bars = fetchBars(resolved, degradations, notes);
        LookbackCalculator.Result priceLookbacks = priceLookbacks(resolved, bars);

        CodeLookupDTO.QuoteView quoteView = quoteView(quote, resolved, degradations, notes);
        CodeLookupDTO.PositionView position = position(bars);

        CodeLookupDTO dto = new CodeLookupDTO(
                code, resolved.resolvedCode(),
                nameOf(resolved, quoteView),
                resolved.kind(), kindLabel(resolved),
                quoteView,
                priceLookbacks.current(),
                priceLookbacks.currentDate() == null ? null : priceLookbacks.currentDate().format(ISO_DATE),
                cells(priceLookbacks), position, valuation,
                List.copyOf(notes), localDateTimeNow().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), false);
        return new Outcome(dto, !degradations.isEmpty());
    }

    /** 池外指数的判定补充说明。写进 notes，让「为什么按指数解释」有据可查。 */
    private static void degenerateToOffPool(String code, List<String> notes) {
        notes.add("6 位纯数字在 A 股与中证指数码段上有重叠（" + code
                + " 这类 000xxx 两边都在用），本次按行情源的回答判定");
    }

    private static String kindLabel(Resolved resolved) {
        return switch (resolved.kind()) {
            case INDEX_FUND -> "池内指数（输入的是它的代表 ETF）";
            case INDEX -> "池内指数";
            case OFF_POOL_INDEX -> "池外指数（" + resolved.route().label() + "）";
            case STOCK, FUND -> marketOf(resolved.resolvedCode()) == null
                    ? "A 股标的" : marketOf(resolved.resolvedCode()).label();
        };
    }

    private static String nameOf(Resolved resolved, CodeLookupDTO.QuoteView quote) {
        if (quote != null && quote.name() != null && !quote.name().isBlank()) {
            return quote.name();
        }
        if (resolved.fund() != null) {
            return resolved.fund().indexName();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 行情
    // ------------------------------------------------------------------

    /**
     * 一份报价，外加「它是哪个源给的」。
     *
     * @param noSuchSecurity 源**明确回了**「没有这只标的」。分配它而不是用 null 表达，
     *                       是因为「没有这只票」与「没问到」必须分开：前者回 404，
     *                       后者回 503
     */
    private record QuoteRef(CodeLookupDTO.QuoteView view, String provider, boolean noSuchSecurity) {}

    /**
     * 取一只标的的行情。主源东财，缺了走腾讯、新浪（各自独立的配额，正是兜底链的用途）。
     *
     * @return 拿到行情 → 值；所有源都**明确回了没有** → {@code null}；
     *         所有源都没问到（网络/结构异常）→ 抛 {@link MarketDataException}；
     *         被限流 → 抛 {@link MarketDataException.MarketDataRateLimitedException}
     */
    private QuoteRef fetchQuote(Resolved resolved, List<String> degradations, List<String> notes) {
        String secid = resolved.secid();
        // 行情表的键是**证券代码**（510300），不是池内指数代码（SH000300）——
        // 用 resolvedCode 去查会永远差一个空，表现成「这个 ETF 没有报价」，
        // 而它明明就在池子里。secid 的点号后面那一段才是证券代码。
        String code = secid.substring(secid.indexOf('.') + 1);

        boolean answered = false;
        try {
            Map<String, StockRow> quotes = marketDataClient.fetchQuotes(List.of(secid));
            answered = true;
            StockRow row = quotes.get(code);
            if (row != null) {
                return new QuoteRef(new CodeLookupDTO.QuoteView(
                        MarketDataClient.PROVIDER_EASTMONEY, sourceLabel(MarketDataClient.PROVIDER_EASTMONEY),
                        row.getName(), row.getPrice(), row.getPctChange(), row.getPeTtm(), row.getPb(),
                        row.getIndustry(), row.getTotalMarketCap()), MarketDataClient.PROVIDER_EASTMONEY, false);
            }
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            // **先记冷却再上抛**：只把 429 返给前端而不锁住自己，用户点一次「再试一次」
            // 就又是一次真实外呼——那正是「每次重试都在把 IP 往更深的封禁里推」。
            cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
            throw e;
        } catch (RuntimeException e) {
            log.warn("代码查询：东财行情不可用 code={}：{}", code, MarketDataClient.shortReason(e));
        }

        // 主源没给到：按「哪个源的配额还没被用掉」逐层兜底。用了哪个源要写在结果里——
        // 新浪那一路不给市值与行业，页面得说得出这些格子是空的、空在哪。
        for (String provider : List.of(AltQuoteSource.PROVIDER_TENCENT, AltQuoteSource.PROVIDER_SINA)) {
            if (cache.inCooldown(provider)) continue;
            try {
                Map<String, MarketDataClient.EtfQuote> got =
                        AltQuoteSource.PROVIDER_SINA.equals(provider)
                                ? altQuoteSource.fetchSinaQuotes(List.of(code))
                                : altQuoteSource.fetchTencentQuotes(List.of(code));
                MarketDataClient.EtfQuote q = got.get(code);
                if (q != null) {
                    degrade(degradations, notes, "行情取自兜底源" + sourceLabel(provider) + "（东财没给到）");
                    return new QuoteRef(new CodeLookupDTO.QuoteView(
                            provider, sourceLabel(provider), q.name(), q.price(), q.pctChange(),
                            q.peTtm(), q.pb(), null, q.marketCap()), provider, false);
                }
                answered = true;   // 这个源答了，只是也没有这只标的
            } catch (MarketDataException.MarketDataRateLimitedException e) {
                cache.enterCooldown(provider);
                throw e;
            } catch (RuntimeException e) {
                log.warn("代码查询：{} 兜底行情不可用 code={}：{}", provider, code,
                        MarketDataClient.shortReason(e));
            }
        }

        if (answered) {
            return null;   // 有人答过「没有这只标的」
        }
        throw new MarketDataException("行情源都没问到 " + code + " 的报价"
                + "（东财、腾讯、新浪三家都不可达），本次未查询");
    }

    // ------------------------------------------------------------------
    // 日线
    // ------------------------------------------------------------------

    /** 长日线（约 {@value #HISTORY_MARGIN_DAYS} 天缓冲之外的十年）。失败只降级价格长档，不编数。 */
    private List<PricePositionCalculator.Bar> fetchBars(Resolved resolved, List<String> degradations,
                                                       List<String> notes) {
        String secid = barsSecid(resolved);
        if (secid == null) {
            return List.of();
        }
        MarketDataClient.KlineOutcome outcome = marketDataClient.fetchKline(secid, lookupKlineLimit);
        if (outcome.throttled()) {
            // 日线与行情是同一个出口 IP。已经被拒了就不能再往下打，交给上层冷却。
            cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
            throw new MarketDataException.MarketDataRateLimitedException(
                    "行情源限流，本次未完成（" + outcome.failureReason() + "）");
        }
        if (!outcome.ok()) {
            degrade(degradations, notes,
                    "日线未取到：" + outcome.failureReason() + "（价格八档与价格位置都为空）");
            return List.of();
        }
        return outcome.bars();
    }

    /**
     * 价格序列用哪个 secid。
     *
     * <p>池内指数取它的**代表 ETF**（与 ETF 日报同一口径：日报看的就是 ETF 的价格）；
     * 池外中证指数用东财的指数形态 {@code 1.<code>}；蛋卷的池外指数（只有一个字母代码）
     * 在行情源里没有可靠的 secid 形态，**不猜**，直接返回 null。
     */
    private static String barsSecid(Resolved resolved) {
        if (resolved.secid() != null) {
            return resolved.secid();
        }
        if (resolved.route() != null && resolved.route().source() == OffPoolIndexResolver.Source.CSINDEX) {
            return "1." + resolved.route().sourceCode();
        }
        return null;
    }

    private static LookbackCalculator.Result priceLookbacks(Resolved resolved,
                                                            List<PricePositionCalculator.Bar> bars) {
        if (bars.isEmpty()) {
            String why = resolved.secid() == null && resolved.route() != null
                    && resolved.route().source() == OffPoolIndexResolver.Source.DANJUAN
                    ? "蛋卷口径的指数没有可用的行情代码（东财不按指数名报价），价格八档未确认"
                    : "未取到日线，价格八档未确认";
            return LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.PRICE, why);
        }
        List<LookbackCalculator.Point> series = new ArrayList<>(bars.size());
        for (PricePositionCalculator.Bar b : bars) {
            series.add(new LookbackCalculator.Point(b.date(), b.close()));
        }
        return LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.PRICE, null);
    }

    private CodeLookupDTO.QuoteView quoteView(QuoteRef quote, Resolved resolved,
                                              List<String> degradations, List<String> notes) {
        if (quote != null) {
            return quote.view();
        }
        if (resolved.secid() != null) {
            // 池内指数：报价没有不代表这个代码是错的（可能停牌，也可能这个源就是不报它）。
            // 个股走到这里时已经在 doLookup 里换成池外指数重试过了，不会再落进这一支。
            degrade(degradations, notes, "行情源明确回了「没有这只标的」的报价，现价与涨跌幅为空");
        } else {
            // **不要伪造一个「行情源说没有」的原因**：池外指数压根没问过行情源，
            // 因为指数代码在报价接口里没有可靠的 secid 形态。两句话指向的是不同的下一步。
            degrade(degradations, notes,
                    "池外指数不取现价与涨跌幅（指数代码在报价源里没有可靠的形态），"
                            + "价格八档与估值照常");
        }
        return null;
    }

    private static CodeLookupDTO.PositionView position(List<PricePositionCalculator.Bar> bars) {
        if (bars.isEmpty()) {
            return new CodeLookupDTO.PositionView(false, null, null, null, null, null,
                    List.of("未取到日线，价格位置未确认"));
        }
        PricePosition p = PricePositionCalculator.compute(bars);
        return new CodeLookupDTO.PositionView(p.isAvailable(), p.getPricePercentile(),
                p.getDrawdownFromHigh(), p.getVsMa250(), p.getBarCount(),
                p.getLastTradeDate() == null ? null : p.getLastTradeDate().format(ISO_DATE),
                List.copyOf(p.getDegradations()));
    }

    // ------------------------------------------------------------------
    // 估值
    // ------------------------------------------------------------------

    private CodeLookupDTO.ValuationView valuation(Resolved resolved, List<String> degradations,
                                                  List<String> notes) {
        return switch (resolved.kind()) {
            case INDEX_FUND, INDEX -> poolValuation(resolved.fund(), degradations, notes);
            case OFF_POOL_INDEX -> offPoolValuation(resolved.route(), degradations, notes);
            case STOCK, FUND -> stockValuation(resolved.resolvedCode(), degradations, notes);
        };
    }

    /**
     * 池内指数。**口径由池子钉死**（章程 §5.3），这里只按 {@code valuationSource} 走对应的那一条：
     *
     * <ul>
     *   <li>{@code csindex}：取**中证官网的完整日频历史**现算滚动分位。这与 ETF 日报算的是
     *       同一条线（同一接口、同一算法、同一窗口），所以页面上八档能与日报逐格对上。
     *       取不到时退回项目库里同口径的已累积行——那是**同一个算法写进库的**，
     *       换的是数据的来源不是口径；换了要说出来（写进 {@code notes}）。</li>
     *   <li>{@code danjuan}：只有项目库累积的行。蛋卷不提供历史序列，
     *       库里有多少就有多少，够不到档位就按 {@link #DANJUAN_NO_HISTORY} 说清楚。</li>
     * </ul>
     */
    private CodeLookupDTO.ValuationView poolValuation(IndexFundPool.Fund fund,
                                                      List<String> degradations, List<String> notes) {
        if (fund == null) {
            return unavailable(SOURCE_CSINDEX, LABEL_CSINDEX_SOURCE, "该指数不在池子里");
        }
        if (!fund.hasValuation()) {
            return unavailable(null, null,
                    "池子里没有给 " + fund.indexName() + " 配估值来源，所以这个指数没有 PE 分位");
        }
        String method = fund.percentileMethod();
        if (SOURCE_CSINDEX.equals(fund.valuationSource()) && fund.hasCsindexCode()) {
            OffPoolIndexResolver.Route route = new OffPoolIndexResolver.Route(
                    OffPoolIndexResolver.Source.CSINDEX, fund.csindexCode(),
                    OffPoolIndexResolver.LABEL_CSINDEX);
            OffPoolIndexClient.Result live = offPoolIndexClient.fetch(route);
            if (live.ok() && live.hasHistory()) {
                notes.add("估值来源：" + LABEL_CSINDEX_SOURCE + "（完整日频历史现算滚动分位，与 ETF 日报同一算法）");
                return fromPercentileSeries(SOURCE_CSINDEX, LABEL_CSINDEX_SOURCE, method,
                        live.currentPe(), live.currentDate(), live.points().size(),
                        live.points().get(0).date(), live.currentDate(),
                        percentileLookbacks(live.points(), null), List.of());
            }
            degrade(degradations, notes, "中证官网没给出 " + fund.csindexCode() + " 的历史（"
                    + live.failureReason() + "），本次改用项目库累积的同口径行");
        }

        // 池内 danjuan，或中证官网取不到时的退路：项目库里同口径的行。
        List<MarketValuationHistory> rows = dbHistory(fund.indexCode(), method);
        if (rows.isEmpty()) {
            return unavailable(fund.valuationSource(), sourceLabelOf(fund.valuationSource()),
                    "项目库与来源都没有 " + fund.indexName() + " 的同口径估值行"
                            + (SOURCE_DANJUAN.equals(fund.valuationSource())
                                    ? "；" + DANJUAN_NO_HISTORY : ""));
        }
        String shortNote = SOURCE_DANJUAN.equals(fund.valuationSource()) ? DANJUAN_NO_HISTORY : null;
        notes.add("估值来源：项目库累积的 " + rows.size() + " 行（口径 " + method + "）");
        return fromRows(rows, sourceLabelOf(fund.valuationSource()), method, shortNote);
    }

    /** 池外指数：按形态定的源实时取。中证系拿得到全历史，蛋卷只有当日值。 */
    private CodeLookupDTO.ValuationView offPoolValuation(OffPoolIndexResolver.Route route,
                                                        List<String> degradations, List<String> notes) {
        OffPoolIndexClient.Result r = offPoolIndexClient.fetch(route);
        if (r.notFound()) {
            // 源明确说了「我这里没有这个代码」→ 404。**不能说成 503**：那会让人一直重试
            // 一个根本不存在的代码，看起来像源在抽风。
            throw new CodeNotFoundException(r.failureReason());
        }
        if (!r.ok()) {
            degrade(degradations, notes,
                    "池外指数 " + route.sourceCode() + " 的估值未取到：" + r.failureReason());
            return unavailable(r.source(), r.label(), r.failureReason());
        }
        String label = sourceLabelOf(r.source());
        notes.add("估值来源：" + label + "，口径 " + r.percentileMethod());
        if (r.hasHistory()) {
            List<RollingPercentile.Point> points = r.points();
            return fromPercentileSeries(r.source(), label, r.percentileMethod(),
                    r.currentPe(), r.currentDate(), points.size(),
                    points.get(0).date(), points.get(points.size() - 1).date(),
                    percentileLookbacks(points, null), List.of());
        }
        // 蛋卷：只有当日值。八档全部写清「源不提供历史」——**不留空格，也不拿别的源的数补**。
        return new CodeLookupDTO.ValuationView(true, r.source(), label, r.percentileMethod(),
                r.currentPe(), r.currentPercentile(),
                r.currentDate() == null ? null : r.currentDate().format(ISO_DATE),
                null, null, null,
                LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.POINTS,
                        DANJUAN_NO_HISTORY).cells().stream().map(CodeLookupService::cell).toList(),
                List.of(DANJUAN_NO_HISTORY));
    }

    /**
     * 个股。**PE 来自东财估值分析的日频历史**，分位由滚动窗口现算
     * （{@link StockValuationClient#METHOD_STOCK_VALUATION_ROLLING_10Y}）。
     *
     * <p>{@code push2} 的 {@code f115} 也在报价里带着，但那是**另一份快照**：它没有历史，
     * 只够回答「今天 PE 是多少」。这里两份都给出去，各标各的来源——把 {@code f115}
     * 当成同一个数会让「同一只票两个 PE」变成一句没人看得懂的话。
     */
    private CodeLookupDTO.ValuationView stockValuation(String code, List<String> degradations,
                                                       List<String> notes) {
        StockValuationClient.History h = stockValuationClient.fetchHistory(code);
        if (!h.ok()) {
            degrade(degradations, notes, "个股估值未取到：" + h.failureReason());
            return unavailable(SOURCE_VALUEANALYSIS, LABEL_VALUEANALYSIS_SOURCE, h.failureReason());
        }
        if (h.daysWithoutPe() > 0) {
            // 亏损或未披露的日子没有 PE。它们是**缺失**，不是 0——留着当 0 会被算成史上最便宜。
            notes.add("估值历史里 " + h.daysWithoutPe() + " 天没有 PE（亏损或未披露），未计入窗口");
        }
        List<RollingPercentile.Point> pePoints = new ArrayList<>();
        for (StockValuationClient.Point p : h.points()) {
            if (p.peTtm() != null) {
                pePoints.add(new RollingPercentile.Point(p.tradeDate(), p.peTtm()));
            }
        }
        if (pePoints.isEmpty()) {
            return unavailable(SOURCE_VALUEANALYSIS, LABEL_VALUEANALYSIS_SOURCE,
                    "这只票的估值历史里没有一天有 PE（可能长期亏损），PE 分位算不出来");
        }
        List<RollingPercentile.Point> clean = RollingPercentile.clean(pePoints);
        String shortNote = String.format(
                "该票的估值历史自 %s 起（约 %.1f 年），不足以确认十年基线",
                clean.get(0).date(),
                java.time.temporal.ChronoUnit.DAYS.between(clean.get(0).date(),
                        clean.get(clean.size() - 1).date()) / 365.25);
        LookbackCalculator.Result lookbacks = percentileLookbacks(clean, shortNote);
        notes.add("估值来源：" + LABEL_VALUEANALYSIS_SOURCE + "，口径 "
                + StockValuationClient.METHOD_STOCK_VALUATION_ROLLING_10Y);
        return fromPercentileSeries(SOURCE_VALUEANALYSIS, LABEL_VALUEANALYSIS_SOURCE,
                StockValuationClient.METHOD_STOCK_VALUATION_ROLLING_10Y,
                clean.get(clean.size() - 1).value(),
                clean.get(clean.size() - 1).date(),
                clean.size(), clean.get(0).date(), clean.get(clean.size() - 1).date(),
                lookbacks, List.of());
    }

    // ------------------------------------------------------------------
    // 估值的小工具
    // ------------------------------------------------------------------

    /** 项目库里同口径的行。{@code from} 留了 {@value #HISTORY_MARGIN_DAYS} 天缓冲，见该常量。 */
    private List<MarketValuationHistory> dbHistory(String indexCode, String method) {
        LocalDate today = localDateNow();
        LocalDate from = today.minusYears(RollingPercentile.WINDOW_YEARS).minusDays(HISTORY_MARGIN_DAYS);
        try {
            return valuationService.historyBetween(indexCode, method, from, today);
        } catch (RuntimeException e) {
            log.warn("代码查询：读估值历史失败 indexCode={} method={}：{}", indexCode, method, e.toString());
            return List.of();
        }
    }

    /** 库里累积的行 → 八档。库里的 {@code pe_percentile} 就是分位本身，不再算一遍滚动。 */
    private CodeLookupDTO.ValuationView fromRows(List<MarketValuationHistory> rows, String sourceLabel,
                                                 String method, String shortHistoryNote) {
        List<LookbackCalculator.Point> series = new ArrayList<>(rows.size());
        for (MarketValuationHistory r : rows) {
            series.add(new LookbackCalculator.Point(r.getTradeDate(), r.getPePercentile()));
        }
        LookbackCalculator.Result lookbacks =
                LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.POINTS, shortHistoryNote);
        MarketValuationHistory last = rows.get(rows.size() - 1);
        return new CodeLookupDTO.ValuationView(true, sourceOf(sourceLabel), sourceLabel, method,
                last.getPeTtm(), last.getPePercentile(),
                last.getTradeDate() == null ? null : last.getTradeDate().format(ISO_DATE),
                rows.size(), rows.get(0).getTradeDate().format(ISO_DATE),
                last.getTradeDate().format(ISO_DATE), cells(lookbacks), List.of());
    }

    /**
     * 一段 PE 观测 → 分位八档：先对每一天算滚动窗口分位，再对分位序列取八档基线。
     *
     * <p>{@code RollingPercentile.rolling} 返回的是**清洗后**序列的分位，所以这里显式先
     * {@link RollingPercentile#clean} 一次再对齐位置——直接按下标对齐未清洗的原序列，
     * 会在第一行坏数据之后整体错位一格，而分位仍然「看起来正常」。
     */
    static LookbackCalculator.Result percentileLookbacks(List<RollingPercentile.Point> pePoints,
                                                        String shortHistoryNote) {
        List<RollingPercentile.Point> clean = RollingPercentile.clean(pePoints);
        if (clean.isEmpty()) {
            return LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.POINTS, shortHistoryNote);
        }
        List<BigDecimal> percentiles = RollingPercentile.rolling(clean);
        List<LookbackCalculator.Point> series = new ArrayList<>(clean.size());
        for (int i = 0; i < clean.size() && i < percentiles.size(); i++) {
            series.add(new LookbackCalculator.Point(clean.get(i).date(), percentiles.get(i)));
        }
        return LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.POINTS, shortHistoryNote);
    }

    /** 已经把分位算好的序列直接给八档（中证官网现算的那条路走的是 {@link #percentileLookbacks}）。 */
    private CodeLookupDTO.ValuationView fromPercentileSeries(String source, String sourceLabel, String method,
                                                             BigDecimal peTtm, LocalDate peDate,
                                                             int historyLength, LocalDate from, LocalDate to,
                                                             LookbackCalculator.Result lookbacks,
                                                             List<String> extraNotes) {
        return new CodeLookupDTO.ValuationView(true, source, sourceLabel, method, peTtm,
                lookbacks.current(), peDate == null ? null : peDate.format(ISO_DATE),
                historyLength, from == null ? null : from.format(ISO_DATE),
                to == null ? null : to.format(ISO_DATE), cells(lookbacks), List.copyOf(extraNotes));
    }

    private static CodeLookupDTO.ValuationView unavailable(String source, String sourceLabel, String reason) {
        return new CodeLookupDTO.ValuationView(false, source, sourceLabel, null, null, null, null,
                null, null, null,
                LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.POINTS, reason)
                        .cells().stream().map(CodeLookupService::cell).toList(),
                List.of(reason));
    }

    private static List<CodeLookupDTO.LookbackCell> cells(LookbackCalculator.Result result) {
        return result.cells().stream().map(CodeLookupService::cell).toList();
    }

    private static CodeLookupDTO.LookbackCell cell(LookbackCalculator.Cell c) {
        return new CodeLookupDTO.LookbackCell(c.label(), c.baseline(),
                c.baselineDate() == null ? null : c.baselineDate().format(ISO_DATE),
                c.change(), c.status());
    }

    private static String sourceOf(String sourceLabel) {
        if (LABEL_CSINDEX_SOURCE.equals(sourceLabel)) return SOURCE_CSINDEX;
        if (LABEL_DANJUAN_SOURCE.equals(sourceLabel)) return SOURCE_DANJUAN;
        if (LABEL_VALUEANALYSIS_SOURCE.equals(sourceLabel)) return SOURCE_VALUEANALYSIS;
        return sourceLabel;
    }

    private static String sourceLabelOf(String source) {
        if (source == null) return null;
        return switch (source) {
            case SOURCE_CSINDEX -> LABEL_CSINDEX_SOURCE;
            case SOURCE_DANJUAN -> LABEL_DANJUAN_SOURCE;
            case SOURCE_VALUEANALYSIS -> LABEL_VALUEANALYSIS_SOURCE;
            default -> source;
        };
    }

    /** 源的名字 → 页面上写给人看的叫法。认不出来的原样写出来，别假装认得。 */
    static String sourceLabel(String provider) {
        if (provider == null) return "未知来源";
        return switch (provider) {
            case MarketDataClient.PROVIDER_EASTMONEY -> "东财";
            case MarketDataClient.PROVIDER_LOCAL -> "本地预取库";
            case AltQuoteSource.PROVIDER_TENCENT -> "腾讯（兜底）";
            case AltQuoteSource.PROVIDER_SINA -> "新浪（兜底）";
            default -> provider;
        };
    }

    // ==================================================================
    // 缓存与时钟
    // ==================================================================

    private CodeLookupDTO cachedResult(String code) {
        Cached hit = results.get(code);
        if (hit == null) return null;
        if (localDateTimeNow().isAfter(hit.at().plusSeconds(CACHE_TTL_SECONDS))) {
            results.remove(code);
            return null;
        }
        return hit.dto().withCacheInfo(hit.at().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), true);
    }

    private void store(String code, CodeLookupDTO dto) {
        results.put(code, new Cached(dto, localDateTimeNow()));
        // 顺手清过期项：这个 map 的键是用户输入的代码，不清理的话换几个代码就永远留着
        if (results.size() > 200) {
            LocalDateTime deadline = localDateTimeNow().minusSeconds(CACHE_TTL_SECONDS);
            results.entrySet().removeIf(e -> e.getValue().at().isBefore(deadline));
        }
    }

    /**
     * 「今天几号」与「现在几点」。做成可覆盖的方法而不是直接 {@code LocalDate.now()}，
     * 是为了让测试钉得住日期——钉不住的话，**同一条用例在工作日绿、在周末红**，
     * 而 CI 哪天跑不由我们定（{@link ScreenerPrefetchTask} 上踩过这个坑）。
     */
    LocalDate localDateNow() {
        return LocalDate.now(SHANGHAI);
    }

    LocalDateTime localDateTimeNow() {
        return LocalDateTime.now(SHANGHAI);
    }

    /** 仅供测试与诊断：丢掉进程内结果缓存。 */
    public void clearCache() {
        results.clear();
    }

    /** 仅供诊断：缓存里现在有几个代码。 */
    public int cacheSize() {
        return results.size();
    }
}