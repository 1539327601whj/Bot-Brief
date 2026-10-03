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
import java.util.TreeMap;
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

    /**
     * 蛋卷的 PE 历史是**周频**快照（见 {@link OffPoolIndexClient#fetchDanjuanHistory(String)}）。
     * 这句话用在**该口径给不出比一周更细的档位**的说明上——目前只有「昨」这一档，
     * 理由见 {@link #withoutTheYesterdayBand}。
     */
    static final String DANJUAN_WEEKLY_NOTE =
            "该指数口径源（蛋卷）给的是周频快照（每周一个观测点），没有比一周更细的观测";

    /**
     * 蛋卷**这个指数**没有 PE 历史序列（实测：不存在的代码也回同一个空数组，两者分不开）。
     * 说的是「这个指数没有」，不是「这个源没有」——后者是旧文案，接上 {@code pe_history}
     * 之后已经不成立了。不能挪作他用：库里有行、只是本次没取到时走的是另一句话。
     */
    static final String DANJUAN_NO_HISTORY =
            "蛋卷没有这个指数的 PE 历史序列，这一档分位算不出来";

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

    /**
     * 兜底源（腾讯）日线一次最多求多少根，见 {@link #tencentKlineLimit()}。
     *
     * <p>与 {@link #lookupKlineLimit} **不是一回事**：那个 2600 是东财的量级，
     * 腾讯日线接口超过 800 就 {@code param error}。合成一个值必然有一边是错的。
     */
    private final int lookupFallbackKlineLimit;

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
            @Value("${screener.lookup-kline-limit:2600}") int lookupKlineLimit,
            @Value("${screener.lookup-fallback-kline-limit:800}") int lookupFallbackKlineLimit) {
        this.marketDataClient = marketDataClient;
        this.altQuoteSource = altQuoteSource;
        this.stockValuationClient = stockValuationClient;
        this.offPoolIndexClient = offPoolIndexClient;
        this.indexPool = indexPool;
        this.valuationService = valuationService;
        this.cache = cache;
        this.lookupKlineLimit = Math.max(1, lookupKlineLimit);
        this.lookupFallbackKlineLimit = Math.max(1, lookupFallbackKlineLimit);
    }

    /**
     * 兜底日线的**一页**要多少根：配置的上限，但不超过主源那个数与腾讯的硬上限。
     *
     * <p>夹取不是洁癖——腾讯对这个参数是**硬拒**：2600 直接回
     * {@code {"code":0,"msg":"param error","data":[]}}，而 {@code lookupKlineLimit}
     * 的默认值正是 2600。照原样传过去，兜底日线会 100% 落空，而且看起来像「腾讯也挂了」。
     *
     * <p>第三项那个 {@link AltQuoteSource#TENCENT_KLINE_MAX_ROWS} **不能省**：
     * 配置只是默认值，改大了接口不会报错，只会悄悄给 640 根，
     * 于是「翻页」翻的是一堆静默截断的页。上限必须由代码兜住。
     *
     * <p>注意这里只定**一页多宽**：总量是 {@link #lookupKlineLimit}，页数由
     * {@link #tencentBars} 算成 {@code ceil(总量 / 本值)}。
     */
    private int tencentKlineLimit() {
        return Math.min(Math.min(lookupFallbackKlineLimit, lookupKlineLimit),
                AltQuoteSource.TENCENT_KLINE_MAX_ROWS);
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

        // **这里原先有一道「冷却中就直接 429」的早退，已经删掉。** 它的前提是
        // 「被东财限流 = 什么都取不到」，而线上实测证明不成立：被封的只有 push2 这一族，
        // 腾讯/新浪（兜底行情与日线）和 datacenter-web（估值）全都是好的。早退把
        // 本可以出数的页面变成了错误页——用户截图撞到的就是这个状态。
        //
        // 但「冷却期内不再外呼」这条纪律**照旧成立**，只是改由构造保证：
        // fetchQuote/fetchBars 在冷却期内**根本不调用** push2/push2his（见各自的方法注释）。
        // 零外呼的路只剩下面命中缓存那一条，而那是东财健康时存下的成功结果。
        CodeLookupDTO cached = cachedResult(code);
        if (cached != null) {
            return cached;
        }

        Outcome outcome = doLookup(code);
        if (!outcome.degraded()) {
            // 只缓存**没有降级**的结果。把一次「中证官网不可达」缓存 5 分钟，
            // 用户点「再试一次」还是同一句话，看起来像功能坏了——
            // 而实际上源早就恢复了。
            store(code, outcome.dto(), outcome.takenAt());
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

    private record Outcome(CodeLookupDTO dto, boolean degraded, LocalDateTime takenAt) {}

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

        Bars bars = fetchBars(resolved, degradations, notes);
        LookbackCalculator.Result priceLookbacks = priceLookbacks(resolved, bars);

        CodeLookupDTO.QuoteView quoteView = quoteView(quote, resolved, degradations, notes);
        CodeLookupDTO.PositionView position = position(bars.bars());

        // **一次查询只读一次「现在」**，DTO 和缓存写的是同一个时刻。
        // 原来这里和 {@link #store} 各读一次，两次差几十微秒，于是同一份结果的
        // snapshotAt 在「这次新取的」和「命中缓存的」之间会跳一个读不出来的量：
        // 页面上没人看得出来，测试里却是随机红（CI 上真的因此红过）。
        LocalDateTime takenAt = localDateTimeNow();
        CodeLookupDTO dto = new CodeLookupDTO(
                code, resolved.resolvedCode(),
                nameOf(resolved, quoteView),
                resolved.kind(), kindLabel(resolved),
                quoteView,
                priceLookbacks.current(),
                priceLookbacks.currentDate() == null ? null : priceLookbacks.currentDate().format(ISO_DATE),
                cells(priceLookbacks), position, valuation,
                List.copyOf(notes), stamp(takenAt), false);
        return new Outcome(dto, !degradations.isEmpty(), takenAt);
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

    /** 一份报价，外加「它是哪个源给的」。 */
    private record QuoteRef(CodeLookupDTO.QuoteView view, String provider) {}

    /** 「约 N 分钟后可再试」。**按 provider 取剩余时间**：兜底源也限流时要报的是它自己的等待量。 */
    private String cooldownMessage(String provider) {
        long left = cache.cooldownRemainingMinutes(provider);
        return "行情源限流冷却中，约 " + left + " 分钟后可再试"
                + "（冷却期内不再外呼，免得把封禁推得更深）";
    }

    /**
     * 取一只标的的行情。主源东财，缺了走腾讯、新浪（各自独立的配额，正是兜底链的用途）。
     *
     * <p><b>被限流不再一律上抛</b>：东财是三个域名里唯一被按 IP 封的那一族，而腾讯/新浪
     * 是独立配额，正是被拒时该用的东西。所以限流只记冷却、继续往下试；只有**兜底也拿不到**
     * 才把 429 交给上层。原来的 {@code catch} 直接 {@code throw}，让下面这个循环永远到不了，
     * 线上表现为「一次查询只发出 1 次外呼，就那 1 次被拒」。
     *
     * @return 拿到行情 → 值；**某个能表示该代码的源明确回了「没有这只标的」** → {@code null}；
     *         东财被限流或冷却中、且兜底也没给到 → 抛
     *         {@link MarketDataException.MarketDataRateLimitedException}；
     *         其余取数失败 → 抛 {@link MarketDataException}
     */
    private QuoteRef fetchQuote(Resolved resolved, List<String> degradations, List<String> notes) {
        String secid = resolved.secid();
        // 行情表的键是**证券代码**（510300），不是池内指数代码（SH000300）——
        // 用 resolvedCode 去查会永远差一个空，表现成「这个 ETF 没有报价」，
        // 而它明明就在池子里。secid 的点号后面那一段才是证券代码。
        String code = secid.substring(secid.indexOf('.') + 1);

        // 东财是不是「问了也白问」。冷却期内**根本不发请求**——这就是「冷却期内不再外呼」
        // 现在唯一还在这里成立的形式（入口那道早退已经删了，见 lookup）。
        boolean eastmoneyUnusable = cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY);

        // **东财成功了、只是没有这一行。** 这是本方法能返回 null 的两条路之一，
        // 也是 doLookup 把个股当池外指数的唯一依据，所以必须与「没问到」严格分开：
        // 一旦把取数失败也算进来，「东财一挂，所有 6 位代码都被读成指数」就回来了。
        boolean eastmoneyAnsweredAbsent = false;

        if (!eastmoneyUnusable) {
            try {
                Map<String, StockRow> quotes = marketDataClient.fetchQuotes(List.of(secid));
                StockRow row = quotes.get(code);
                if (row != null) {
                    return new QuoteRef(new CodeLookupDTO.QuoteView(
                            MarketDataClient.PROVIDER_EASTMONEY,
                            sourceLabel(MarketDataClient.PROVIDER_EASTMONEY),
                            row.getName(), row.getPrice(), row.getPctChange(), row.getPeTtm(), row.getPb(),
                            row.getIndustry(), row.getTotalMarketCap()),
                            MarketDataClient.PROVIDER_EASTMONEY);
                }
                eastmoneyAnsweredAbsent = true;
            } catch (MarketDataException.MarketDataRateLimitedException e) {
                // **记冷却，但不再上抛**：上抛会让下面的兜底循环永远到不了，而腾讯/新浪
                // 的配额跟东财是分开的——它们很可能完全可用。
                cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
                eastmoneyUnusable = true;
                log.warn("代码查询：东财行情被限流 code={}，转兜底源：{}",
                        code, MarketDataClient.shortReason(e));
            } catch (RuntimeException e) {
                log.warn("代码查询：东财行情不可用 code={}：{}", code, MarketDataClient.shortReason(e));
            }
        }

        // 兜底源能不能表示这个代码。腾讯与新浪共用同一套市场前缀映射
        // （{@code AltQuoteSource.symbolsOf} 用的就是 tencentSymbol），判一次覆盖两路。
        //
        // **这个判断不能省，也不能只当备注**：表示不了时那两个方法一个请求都不发、
        // 直接返回空 Map，若把那当成「源答了、只是没有这只标的」，上面那条注释要防的事
        // 就会从这条缝里钻回来。所以表示不了就**整个循环都不进**——那是「没问」，
        // 不是「问了没有」。
        boolean representable = AltQuoteSource.tencentSymbol(code) != null;
        boolean fallbackAnsweredAbsent = false;
        String throttledFallback = null;

        // 主源没给到：按「哪个源的配额还没被用掉」逐层兜底。用了哪个源要写在结果里——
        // 新浪那一路不给市值与行业，页面得说得出这些格子是空的、空在哪。
        for (String provider : representable
                ? List.of(AltQuoteSource.PROVIDER_TENCENT, AltQuoteSource.PROVIDER_SINA)
                : List.<String>of()) {
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
                            q.peTtm(), q.pb(), null, q.marketCap()), provider);
                }
                // 走到这里说明**真的发过请求**，且确实没有这一行
                fallbackAnsweredAbsent = true;
            } catch (MarketDataException.MarketDataRateLimitedException e) {
                // 记冷却后**继续试下一个源**：新浪与腾讯是两套独立配额，一个被打回来
                // 不代表另一个也不能用。原来这里上抛，等于把这条兜底链自己掐断。
                cache.enterCooldown(provider);
                throttledFallback = provider;
            } catch (RuntimeException e) {
                log.warn("代码查询：{} 兜底行情不可用 code={}：{}", provider, code,
                        MarketDataClient.shortReason(e));
            }
        }

        if (eastmoneyUnusable) {
            // **绝不返回 null**：那会让 doLookup 把它读成「池外指数」，于是一个被限流的
            // 个股会变成 404「查不到代码」——比报错更难查，而且换个时间点结果就变了。
            throw new MarketDataException.MarketDataRateLimitedException(
                    cooldownMessage(MarketDataClient.PROVIDER_EASTMONEY));
        }
        if (eastmoneyAnsweredAbsent || fallbackAnsweredAbsent) {
            return null;   // 能表示这个代码的源明确回了「没有这只标的」
        }
        if (throttledFallback != null) {
            // 东财这次是**非限流**地失败了，兜底又被打回来：能等的是兜底源，报它的时间。
            throw new MarketDataException.MarketDataRateLimitedException(
                    cooldownMessage(throttledFallback));
        }
        throw new MarketDataException("行情源都没问到 " + code + " 的报价（"
                + (representable
                        ? "东财、腾讯、新浪三家本次都没给出结果"
                        : "东财本次没给出结果；腾讯/新浪不覆盖这串代码代表的市场")
                + "），本次未查询");
    }

    // ------------------------------------------------------------------
    // 日线
    // ------------------------------------------------------------------

    /**
     * 一段日线，外加「它是谁给的」。
     *
     * @param provider 非 null 表示这段来自兜底源。{@link #priceLookbacks} 据此给出
     *                 **来源专属**的「历史不够长」说明，而不是通用的那一句
     */
    private record Bars(List<PricePositionCalculator.Bar> bars, String provider) {
        static final Bars EMPTY = new Bars(List.of(), null);
    }

    /**
     * 长日线（约 {@value #HISTORY_MARGIN_DAYS} 天缓冲之外的十年）。
     *
     * <p>东财不可用（被限流、冷却中、或问了没给到）时退到腾讯日线；腾讯也没有才降级为空。
     * **只降级，不再上抛**：日线是增值项，价格与估值都还在，为它把整页变成错误页不划算——
     * 原来那个 429 正是「东财一挂整页打不开」的另一半原因。
     */
    private Bars fetchBars(Resolved resolved, List<String> degradations, List<String> notes) {
        String secid = barsSecid(resolved);
        if (secid == null) {
            return Bars.EMPTY;
        }
        String code = secid.substring(secid.indexOf('.') + 1);

        String eastmoneyReason = null;
        // **这里必须重新查一次冷却**，不能沿用入口处捕获的布尔值：fetchQuote 自己就可能
        // 刚把东财关进冷却，那样日线若不复查就还会真打一次 push2his——正是要避免的那一下。
        if (!cache.inCooldown(MarketDataClient.PROVIDER_EASTMONEY)) {
            MarketDataClient.KlineOutcome outcome = marketDataClient.fetchKline(secid, lookupKlineLimit);
            if (outcome.ok()) {
                return new Bars(outcome.bars(), null);
            }
            if (outcome.throttled()) {
                // 日线与行情是同一个出口 IP。已经被拒了就不能再往下打。
                cache.enterCooldown(MarketDataClient.PROVIDER_EASTMONEY);
            }
            eastmoneyReason = outcome.failureReason();
        }

        // 兜底日线。**腾讯的键是共享的**（qt.gtimg.cn 与 web.ifzq.gtimg.cn 都归 tencent），
        // 所以先看它有没有在冷却——同一次查询里腾讯行情刚被限流、这里又立刻打腾讯日线，
        // 正是项目明令禁止的「重试把封禁推得更深」。低估精选已经在这么做。
        if (!cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)
                && AltQuoteSource.tencentSymbol(code) != null) {
            Bars alt = tencentBars(code);
            if (alt.bars().isEmpty()) {
                log.warn("代码查询：腾讯日线一根都没给 code={}", code);
            } else {
                degrade(degradations, notes, "日线取自" + sourceLabel(AltQuoteSource.PROVIDER_TENCENT)
                        + "（东财没给到），价格位置据此计算");
                return alt;
            }
        }

        degrade(degradations, notes, "日线未取到："
                + (eastmoneyReason != null ? eastmoneyReason : "行情源限流中，本次未外呼")
                + "（价格八档与价格位置都为空）");
        return Bars.EMPTY;
    }

    /**
     * 腾讯日线，**按日期区间往回翻页**，直到够 {@link #lookupKlineLimit} 根。
     *
     * <h2>为什么必须翻页</h2>
     *
     * <p>腾讯一次的 {@code count} 硬上限是 800 根（≈3.3 年），而「代码查询」的八档要看到十年，
     * 东财那个 2600 直接传过去是 {@code {"msg":"param error"}}。实测：只给 {@code end}
     * 时返回的是该区间末尾的 800 根，于是「上一页最早那天减一天」就是下一页的 {@code end}
     * （5 页 3596 根、零重复日期、连续无洞）。
     *
     * <h2>三个终止条件，缺一个都会出事</h2>
     *
     * <ul>
     *   <li>{@code 收够 lookupKlineLimit} —— 正常出口。2600/800 → 4 页。</li>
     *   <li>{@code 日期数没有增长} —— <b>唯一的防死循环闸门</b>：源哪天开始忽略 {@code end}
     *       并每次都回同一批，收够那个条件就永远为假，会一直打下去。</li>
     *   <li>{@code 页数上限} —— {@code ceil(lookupKlineLimit / 页大小)}。第三个兜底，
     *       让外呼次数有一个**不依赖源行为**的硬上界。</li>
     * </ul>
     *
     * <p>中途被限流：{@code enterCooldown} 后**保留已经拿到的页**。它们是从最近往回的
     * 连续段，每一根都是源真给了的，丢掉等于把已经拿到的十年变成一根都没有；缺的那一段由
     * {@link #shortHistoryNote} 照实际根数写出来。**绝不重试**——冷却期内连这一轮都不再发。
     *
     * <h2>代价：东财被限流时，一次点击 4 次外呼（有意接受）</h2>
     *
     * <p>这不是「多打几个接口试试」——次数由 {@code ceil(want / pageSize)} 定死，与源的行为无关，
     * 而且每次点击之间的结果不进缓存、也不重试。四条都在 {@code tencent} 这个额度键下排队，
     * 150ms 的闸门照样把它们摊匀。反过来说：**因为东财被封才走到这条路**，
     * 治本是给它单独配出口 IP（见项目笔记），不是把这里改回「只取 800 根」。
     */
    private Bars tencentBars(String code) {
        int pageSize = tencentKlineLimit();
        int want = lookupKlineLimit;
        int maxPages = Math.max(1, (want + pageSize - 1) / pageSize);
        // 按日期去重：页边界（上一页最早那天）必然会重叠一根，靠 put 覆盖而不是去重逻辑。
        // TreeMap 顺带把顺序定死，源反着回也不影响入库的曲线。
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();

        LocalDate end = null;
        for (int page = 0; page < maxPages; page++) {
            if (cache.inCooldown(AltQuoteSource.PROVIDER_TENCENT)) {
                break;
            }
            MarketDataClient.KlineOutcome outcome = altQuoteSource.fetchTencentKline(code, end, pageSize);
            if (outcome == null) {
                // 生产不会返回 null（它把失败都塞在返回值里），但 mock 未 stub 时是 null，
                // 不判空就是 NPE —— 一样的写法在 StockScreenerService 里也有。
                log.warn("代码查询：腾讯日线返回了 null code={}", code);
                break;
            }
            if (!outcome.ok()) {
                if (outcome.throttled()) {
                    cache.enterCooldown(AltQuoteSource.PROVIDER_TENCENT);
                }
                break;
            }
            int before = byDate.size();
            LocalDate earliest = null;
            for (PricePositionCalculator.Bar bar : outcome.bars()) {
                if (bar == null || bar.date() == null) {
                    continue;
                }
                byDate.put(bar.date(), bar.close());
                if (earliest == null || bar.date().isBefore(earliest)) {
                    earliest = bar.date();
                }
            }
            if (earliest == null || byDate.size() == before) {
                break;   // 没有比手上更早的日期了：要么已到头，要么源没理 end
            }
            if (byDate.size() >= want) {
                break;
            }
            end = earliest.minusDays(1);
        }

        if (byDate.isEmpty()) {
            return Bars.EMPTY;
        }
        List<PricePositionCalculator.Bar> bars = new ArrayList<>(byDate.size());
        for (Map.Entry<LocalDate, BigDecimal> e : byDate.entrySet()) {
            bars.add(new PricePositionCalculator.Bar(e.getKey(), e.getValue()));
        }
        return new Bars(bars, AltQuoteSource.PROVIDER_TENCENT);
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

    private static LookbackCalculator.Result priceLookbacks(Resolved resolved, Bars bars) {
        if (bars.bars().isEmpty()) {
            String why = resolved.secid() == null && resolved.route() != null
                    && resolved.route().source() == OffPoolIndexResolver.Source.DANJUAN
                    ? "蛋卷口径的指数没有可用的行情代码（东财不按指数名报价），价格八档未确认"
                    : "未取到日线，价格八档未确认";
            return LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.PRICE, why);
        }
        List<LookbackCalculator.Point> series = new ArrayList<>(bars.bars().size());
        for (PricePositionCalculator.Bar b : bars.bars()) {
            series.add(new LookbackCalculator.Point(b.date(), b.close()));
        }
        return LookbackCalculator.compute(series, LookbackCalculator.ChangeStyle.PRICE, shortHistoryNote(bars));
    }

    /**
     * 「五年/十年为什么是空的」的来源专属说明，给 {@link LookbackCalculator} 替换掉它那句
     * 通用的「历史不足」。
     *
     * <p>兜底日线只有约 800 根（≈3.3 年），五年/十年必然取不到——但**那不是数据本身的问题，
     * 是源的上限**，通用文案会把下一步指向错的地方。所以这里用**实际根数与最早交易日**拼，
     * 不硬写「3 年」：腾讯哪天改了上限，页面上这句话仍然是真的。
     *
     * <p>东财那条路返回 null，让 {@code LookbackCalculator} 用它的通用文案——那时「历史不足」
     * 确实是标的自身上市时间短，不是谁的限制。
     */
    private static String shortHistoryNote(Bars bars) {
        if (bars.provider() == null || bars.bars().isEmpty()) {
            return null;
        }
        LocalDate first = bars.bars().stream()
                .map(PricePositionCalculator.Bar::date)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
        return "日线取自" + sourceLabel(bars.provider()) + "，本次只有 " + bars.bars().size() + " 根"
                + (first == null ? "" : "（最早 " + first.format(ISO_DATE) + "）")
                + "，超出这个跨度的档位未确认";
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
     *   <li>{@code danjuan}：先取蛋卷的 **PE 历史**（{@link OffPoolIndexClient#fetchDanjuanHistory}，
     *       **周频**快照）现算滚动分位；取不到才退回项目库累积的行。库里的行是**同一个源
     *       自己算的分位**，两者实测差约 1 个百分点（序列密度不同），所以换了要说出来。</li>
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

        // 池内 danjuan：先要蛋卷的 PE 历史（周频）。
        if (SOURCE_DANJUAN.equals(fund.valuationSource())) {
            CodeLookupDTO.ValuationView live = danjuanHistoryView(fund, method, degradations, notes);
            if (live != null) {
                return live;
            }
        }

        // 池内 danjuan 取不到历史时，或中证官网取不到时的退路：项目库里同口径的行。
        List<MarketValuationHistory> rows = dbHistory(fund.indexCode(), method);
        if (rows.isEmpty()) {
            return unavailable(fund.valuationSource(), sourceLabelOf(fund.valuationSource()),
                    "项目库与来源都没有 " + fund.indexName() + " 的同口径估值行"
                            + (SOURCE_DANJUAN.equals(fund.valuationSource())
                                    ? "；" + DANJUAN_NO_HISTORY : ""));
        }
        // 退路的缺档原因**按实际行数拼**，不能沿用 DANJUAN_NO_HISTORY：那条路现在的含义是
        // 「蛋卷这次没给到历史」，不是「蛋卷没有历史」——两者在页面上是不同的话。
        String shortNote = "本次只有项目库累积的 " + rows.size() + " 行（最早 "
                + rows.get(0).getTradeDate() + "），超出这个跨度的档位未确认";
        notes.add("估值来源：项目库累积的 " + rows.size() + " 行（口径 " + method + "）");
        return fromRows(rows, sourceLabelOf(fund.valuationSource()), method, shortNote);
    }

    /**
     * 蛋卷的 PE 历史 → 八档。取不到（接口回空、不可达、库路径之前就失败）返回 {@code null}，
     * 由调用方掉回项目库那条路。
     *
     * <p>三条不能省的细节：
     * <ul>
     *   <li>{@code hist == null} 要判：生产不会返回 null，但测试里它是 mock，未 stub 时是 null。</li>
     *   <li>用 {@link IndexFundPool.Fund#danjuanCode()} 而不是 {@code indexCode()}——前者才是
     *       蛋卷的入参，后者是**项目库的键**。当前 9 条恰好相等，但那是巧合。</li>
     *   <li>{@link #DANJUAN_WEEKLY_NOTE} 要随结果一起给出去，「昨」这一档是按它留空的。</li>
     * </ul>
     */
    private CodeLookupDTO.ValuationView danjuanHistoryView(IndexFundPool.Fund fund, String method,
                                                           List<String> degradations, List<String> notes) {
        OffPoolIndexClient.Result hist = offPoolIndexClient.fetchDanjuanHistory(fund.danjuanCode());
        if (hist == null || !hist.ok() || !hist.hasHistory()) {
            degrade(degradations, notes, "蛋卷没给出 " + fund.danjuanCode() + " 的 PE 历史（"
                    + (hist == null ? "本次未取到" : hist.failureReason())
                    + "），本次改用项目库累积的同口径行");
            return null;
        }
        List<RollingPercentile.Point> points = hist.points();
        notes.add("估值来源：" + LABEL_DANJUAN_SOURCE + "的 PE 历史（周频快照，约每周一个观测点，"
                + points.size() + " 期，最早 " + points.get(0).date()
                + "），分位按本项目的滚动十年算法现算——与蛋卷自家页面的 pe_percentile "
                + "实测差约 1 个百分点（它内部用的序列更密）");
        return fromPercentileSeries(SOURCE_DANJUAN, LABEL_DANJUAN_SOURCE, method,
                hist.currentPe(), hist.currentDate(), points.size(),
                points.get(0).date(), hist.currentDate(),
                withoutTheYesterdayBand(
                        percentileLookbacks(points, weeklyShortNote(points)),
                        DANJUAN_WEEKLY_NOTE),
                List.of());
    }

    /**
     * 周频序列「历史不够长」时替换用的说明。
     *
     * <p>不能传 null：{@code LookbackCalculator} 只在「最早观测晚于目标日」这一种缺档上用它，
     * 而蛋卷的序列普遍只有 8~10 年，**「十年」这一档正落在这个分支里**（实测 {@code CSI716567}
     * 只有 462 期、最早 2017-10）。传 null 会让它退回通用的「历史不足」，指不到「这个口径是
     * 周频、这次有多少期」。
     */
    private static String weeklyShortNote(List<RollingPercentile.Point> points) {
        return "蛋卷的 PE 历史是周频快照，本次只有 " + points.size() + " 期（最早 "
                + points.get(0).date() + "），超出这个跨度的档位未确认";
    }

    /** 池外指数：按形态定的源实时取。中证系拿得到全历史，蛋卷要再问一次历史接口。 */
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
        // 池外蛋卷：{@code dj} 那一支只有当日值，历史得再问一次 pe_history。
        // 这是**同一次点击里的第二次外呼**（都在 danjuan 这个额度键下），有意接受：
        // 清单那一支还要负责「这个代码存不存在」的 404 判定，合并掉它会丢掉那个语义。
        if (SOURCE_DANJUAN.equals(r.source())) {
            CodeLookupDTO.ValuationView live = offPoolDanjuanHistory(route, r, degradations, notes);
            if (live != null) {
                return live;
            }
        }
        // 蛋卷确实没有这个指数的历史。八档全部写清「源不提供历史」——**不留空格，
        // 也不拿别的源的数补**。
        return new CodeLookupDTO.ValuationView(true, r.source(), label, r.percentileMethod(),
                r.currentPe(), r.currentPercentile(),
                r.currentDate() == null ? null : r.currentDate().format(ISO_DATE),
                null, null, null,
                LookbackCalculator.compute(List.of(), LookbackCalculator.ChangeStyle.POINTS,
                        DANJUAN_NO_HISTORY).cells().stream().map(CodeLookupService::cell).toList(),
                List.of(DANJUAN_NO_HISTORY));
    }

    /**
     * 池外蛋卷指数的 PE 历史 → 八档。取不到返回 {@code null}，由调用方保留「只有当日值」
     * 那条写法。
     *
     * <p>**不因为这条路失败就说 404**：接口对「没有这个代码」和「这个代码没有历史」
     * 回的是同一个空数组（实测），分不开，所以这里只降级。
     */
    private CodeLookupDTO.ValuationView offPoolDanjuanHistory(OffPoolIndexResolver.Route route,
                                                             OffPoolIndexClient.Result current,
                                                             List<String> degradations,
                                                             List<String> notes) {
        OffPoolIndexClient.Result hist = offPoolIndexClient.fetchDanjuanHistory(route.sourceCode());
        if (hist == null || !hist.ok() || !hist.hasHistory()) {
            degrade(degradations, notes, "蛋卷没给出 " + route.sourceCode() + " 的 PE 历史（"
                    + (hist == null ? "本次未取到" : hist.failureReason()) + "），八档未确认");
            return null;
        }
        List<RollingPercentile.Point> points = hist.points();
        notes.add("估值来源：" + sourceLabelOf(SOURCE_DANJUAN) + "的 PE 历史（周频快照，约每周"
                + "一个观测点，" + points.size() + " 期，最早 " + points.get(0).date()
                + "），分位按本项目的滚动十年算法现算——与蛋卷自家页面的 pe_percentile "
                + "实测差约 1 个百分点（它内部用的序列更密）");
        // 当前 PE 用 dj 清单那支给的（它就是蛋卷的当日值），序列末尾的 PE 与它一致；
        // 分位用现算的，所以这里不再引用 current.currentPercentile()——蛋卷自家那个数
        // 与现算相差约 1pp，两个并排出现会变成「同一只指数两个分位」。
        return fromPercentileSeries(current.source(), sourceLabelOf(current.source()),
                current.percentileMethod(), current.currentPe(), current.currentDate(),
                points.size(), points.get(0).date(), points.get(points.size() - 1).date(),
                withoutTheYesterdayBand(
                        percentileLookbacks(points, weeklyShortNote(points)),
                        DANJUAN_WEEKLY_NOTE),
                List.of());
    }

    /**
     * 周频序列的「昨」这一档要空掉，换成 {@link #DANJUAN_WEEKLY_NOTE}。
     *
     * <p>理由：取档那一步——{@link LookbackCalculator#STALE_DAYS} 是 15 天，
     * 足够把「最近一次周频观测」当成「昨」；而那次观测在这类序列里**同时就是最后一点**，
     * 也就是同一行开头的「今」。于是「昨」恒等于「今」、差值恒为 {@code +0.00}：
     * 把一个「这一档没有观测」写成了「没有变化」，正是章程禁止的把未知写成已知。
     * 「周」及以后照填——它们的基线是**另外的**观测点，是有意义的对比。
     *
     * <p>只动已经取到值的那一格：本来就缺的（空序列、历史不足）保留它自己的原因，不覆盖。
     * 用的是 record 重建，{@code baseline == null ⟺ status != null} 这条不变式照守。
     */
    private static LookbackCalculator.Result withoutTheYesterdayBand(
            LookbackCalculator.Result r, String reason) {
        List<LookbackCalculator.Cell> cells = new ArrayList<>(r.cells().size());
        for (LookbackCalculator.Cell c : r.cells()) {
            cells.add("昨".equals(c.label()) && c.present()
                    ? new LookbackCalculator.Cell(c.label(), null, null, null, reason)
                    : c);
        }
        return new LookbackCalculator.Result(r.current(), r.currentDate(), List.copyOf(cells));
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
        return hit.dto().withCacheInfo(stamp(hit.at()), true);
    }

    private void store(String code, CodeLookupDTO dto, LocalDateTime takenAt) {
        results.put(code, new Cached(dto, takenAt));
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

    /**
     * 时间戳的页面写法：**截到秒**。
     *
     * <p>这个字符串会被前端原样印出来（「数据时间 …」）。纳秒对读它的人没有任何信息，
     * 只是噪音——而它也正是「同一次查询能出现两个不同时刻」的唯一原因，
     * 截掉相当于在 {@link #lookup} 那条「一次查询只读一次时钟」后面再加一道保险。
     *
     * <p>秒级精度足够回答章程 §6 要的那个问题：这个数是哪一刻取的。
     */
    private static String stamp(LocalDateTime at) {
        return at.withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
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