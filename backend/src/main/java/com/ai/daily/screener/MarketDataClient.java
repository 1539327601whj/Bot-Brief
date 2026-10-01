package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 东财行情外呼。字段 ID 与含义见 {@code automation/agents/stock_screening_rules.md} 第 2 节。
 *
 * <h2>为什么这里不再用 {@code /api/qt/clist/get}</h2>
 *
 * <p>原来全市场快照走的是 clist。这条路径**触发东财按 IP 的限流封禁**：
 * 一次扫描要「组合请求 × 3 个域名重试，失败再按 4 个板块各翻最多 5 页」，
 * 单次最多 20 多次调用，而页面一打开就自动跑一次。开几次页面，
 * 这个 IP 在 clist 上就被封了——症状是 TCP/TLS 都建得起来、请求也发得出去，
 * 服务端却一个字节都不回就把连接掐掉（浏览器显示 {@code ERR_EMPTY_RESPONSE}，
 * Python 报 {@code RemoteDisconnected}，Java 报 {@code ResourceAccessException} 包 {@code NoHttpResponseException}）。
 * 它**不是**网络不通：同一个域名下的 {@code kline/get} 与 {@code ulist.np/get} 全都正常。
 *
 * <p>现在的取数拆成两段，域名也拆开：
 * <ol>
 *   <li><b>清单</b>走 {@code datacenter-web.eastmoney.com}（独立域名，抗封禁）。
 *       只问一句话：最新交易日里总市值前 N 只是谁。一次请求。</li>
 *   <li><b>基本面</b>走 {@code ulist.np/get}。它与 clist 共用同一套字段系统，
 *       实测返回的 f2…f133 与 clist **逐字段一致**，所以章程的规则与因子一行都不用改。</li>
 * </ol>
 *
 * <p>两条纪律由此而来：**行情域名只留一个、失败不跨域名重试**（重试只会三倍加深封禁），
 * 以及**认得出「被限流」**（{@link #isThrottled}）后立刻停手。
 */
@Slf4j
@Component
public class MarketDataClient {

    private static final String UT = "fa5fd1943c7bdc76815634f86e88ea48";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // ------------------------------------------------------------------
    // 清单（datacenter-web）
    // ------------------------------------------------------------------

    /**
     * 清单域名与行情域名**刻意不同**。除了分担封禁风险，它还提供了一条判断依据：
     * 行情域名被封时清单域名通常仍然是通的——这正好说明「是被限流了」而不是「网络断了」。
     */
    static final String LIST_HOST = "https://datacenter-web.eastmoney.com";
    static final String LIST_PATH = "/api/data/v1/get";
    static final String LIST_REFERER = "https://data.eastmoney.com/";
    static final String LIST_REPORT = "RPT_VALUEANALYSIS_DET";

    /**
     * 清单只取**四列**：谁、什么市场、叫什么、总市值多大。
     *
     * <p>刻意不取 PE/PB/行业——那些一律以行情接口为准。清单里也带这些列，
     * 但那是另一份快照，两份混用会出现「同一只标的 PE 有两个值」而无人察觉，
     * 正是章程反复禁止的跨口径比较。
     */
    static final String LIST_COLUMNS = "SECURITY_CODE,SECUCODE,SECURITY_NAME_ABBR,TOTAL_MARKET_CAP";

    // ------------------------------------------------------------------
    // 行情（ulist）
    // ------------------------------------------------------------------

    /**
     * 行情域名**只留一个**，且失败不换域名重试。
     *
     * <p>原来的写法是三个域名依次回退。在限流这个场景下那是反效果：
     * 东财是按 IP 计数的（实测 clist 被封时三个域名**同时**全挂），
     * 换域名成功不了，只会把失败调用数乘三、把封禁推得更深。
     */
    static final String QUOTE_HOST = "https://push2.eastmoney.com";
    static final String ULIST_PATH = "/api/qt/ulist.np/get";
    static final String KLINE_PATH = "/api/qt/stock/kline/get";
    static final String QUOTE_REFERER = "https://quote.eastmoney.com/center/gridlist.html";

    /**
     * 日线域名。与行情域名分开，且只留一个，理由同上。
     */
    static final String KLINE_HOST = "https://push2his.eastmoney.com";

    /**
     * 全套基本面字段，与章程 2.1 节逐条对应。{@code ulist} 与 {@code clist} 共用这套字段，
     * 换接口不换字段，规则层无感知。
     */
    static final String QUOTE_FIELDS =
            "f12,f14,f2,f3,f6,f8,f20,f21,f23,f24,f25,f26,f37,f41,f46,f49,f57,f100,f113,f115,f133";

    private final RestTemplate restTemplate;
    private final int klineLimit;
    private final int poolSize;
    private final int quoteBatchSize;

    public MarketDataClient(
            @Qualifier("marketRestTemplate") RestTemplate restTemplate,
            @Value("${screener.kline-limit:250}") int klineLimit,
            @Value("${screener.pool-size:500}") int poolSize,
            @Value("${screener.quote-batch-size:50}") int quoteBatchSize) {
        this.restTemplate = restTemplate;
        this.klineLimit = klineLimit;
        this.poolSize = Math.max(1, poolSize);
        this.quoteBatchSize = Math.max(1, quoteBatchSize);
    }

    /** 一次日线抓取的结果：成功给 bars，失败给出可读原因（不抛，由上层决定降级还是淘汰）。 */
    public record KlineOutcome(List<PricePositionCalculator.Bar> bars, String failureReason,
                               boolean throttled) {
        public boolean ok() { return bars != null && !bars.isEmpty(); }

        public static KlineOutcome ok(List<PricePositionCalculator.Bar> bars) {
            return new KlineOutcome(bars, null, false);
        }

        public static KlineOutcome failed(String reason) {
            return new KlineOutcome(List.of(), reason, false);
        }

        /** 被限流导致的失败。上层据此进入冷却，避免下一次点击又打一遍。 */
        public static KlineOutcome throttled(String reason) {
            return new KlineOutcome(List.of(), reason, true);
        }
    }

    /**
     * 一次全市场快照的结果。带上清单侧的计数，是为了让页面的口径摘要能说清
     * 「池子是怎么来的、漏掉了多少」——只返回一个 List 的话这些信息就丢了。
     *
     * @param rows        最终参与筛选的标的（行情齐全的）
     * @param listedCount 清单里按市值取到的条数
     * @param missing     清单里有、行情没回来的条数（已剔除）
     * @param tradeDate   清单所属交易日
     * @param capFloor    池子里最小的总市值（元），可能为 null
     */
    public record UniverseSnapshot(List<StockRow> rows, int listedCount, int missing,
                                   LocalDate tradeDate, BigDecimal capFloor) {}

    // ==================================================================
    // 全市场基本面快照
    // ==================================================================

    /**
     * 全市场快照：先问清单要「总市值前 {@code screener.pool-size} 只」，再用行情批量补齐基本面。
     *
     * <p><b>池子是市值前 N，不是全市场。</b>这是拿请求量换覆盖面的取舍：章程的市值门槛是
     * 稳健 ≥200 亿 / 成长 ≥50 亿，只保留市值靠前的标的能同时压住池子和请求数。
     * 代价是市值门槛以下的标的不在视野里，页面会把实际市值下限写进口径摘要，
     * 让人看得见这个边界，而不是以为筛过了全市场。
     *
     * @throws MarketDataException 取数失败。**必须抛，不能返回空列表假装「今天没有候选」**
     */
    public UniverseSnapshot fetchUniverse() {
        LocalDate tradeDate = latestTradeDate();
        List<StockRow> listed = fetchListByMarketCap(tradeDate);
        if (listed.isEmpty()) {
            throw new MarketDataException("东财清单接口在交易日 " + tradeDate + " 返回了空列表");
        }

        List<String> secids = new ArrayList<>();
        for (StockRow r : listed) secids.add(r.secid());
        Map<String, StockRow> quotes = fetchQuotes(secids);

        List<StockRow> rows = new ArrayList<>();
        int missing = 0;
        for (StockRow base : listed) {
            StockRow q = quotes.get(base.getCode());
            if (q == null) {
                // 行情没回来就不参与打分：缺字段的标的按章程本来就该剔除，
                // 但更该做的是**根本不放进池子**，否则它会被记进某条排雷规则的命中数里，
                // 让「哪条规则拦下了多少只」这个摘要失真。
                missing++;
                continue;
            }
            rows.add(q);
        }
        if (rows.isEmpty()) {
            throw new MarketDataException("清单取到 " + listed.size()
                    + " 只，但行情一只都没取到，本次筛选未执行");
        }
        if (missing > 0) {
            log.warn("清单里有 {} 只没有取到行情，已从池子中剔除", missing);
        }

        BigDecimal capFloor = null;
        for (StockRow r : listed) {
            BigDecimal cap = r.effectiveMarketCap();
            if (cap == null) continue;
            if (capFloor == null || cap.compareTo(capFloor) < 0) capFloor = cap;
        }
        log.info("全市场快照就绪：清单 {} 只，行情补齐 {} 只，剔除 {} 只",
                listed.size(), rows.size(), missing);
        return new UniverseSnapshot(rows, listed.size(), missing, tradeDate, capFloor);
    }

    /**
     * 拼清单请求 URL。**刻意手拼、不走 {@code UriComponentsBuilder}**：{@code filter} 里的
     * 括号、引号、等号必须原样出现在查询串里，服务端用 ANTLR 解析它且不做 URL 解码，
     * 一旦被百分号编码就会直接回 {@code 参数预处理错误: NoViableAltException}。
     * 这与 clist 的 {@code fs} 正好相反（那边必须编码），所以这里把规则写成代码而不是靠自觉。
     */
    static String listUrl(String columns, int pageSize, String sortColumn,
                          Integer sortType, LocalDate filterDate) {
        StringBuilder sb = new StringBuilder(LIST_HOST).append(LIST_PATH).append('?');
        sb.append("reportName=").append(LIST_REPORT);
        sb.append("&columns=").append(columns);
        sb.append("&pageSize=").append(pageSize);
        sb.append("&pageNumber=1");
        sb.append("&sortColumns=").append(sortColumn);
        if (sortType != null) sb.append("&sortTypes=").append(sortType);
        if (filterDate != null) sb.append("&filter=(TRADE_DATE='").append(filterDate).append("')");
        sb.append("&source=WEB&client=WEB");
        return sb.toString();
    }

    /** 清单所属的最新交易日。单独问一次，比「猜今天」可靠：周末与节假日不能想当然。 */
    LocalDate latestTradeDate() {
        String url = listUrl("TRADE_DATE", 1, "TRADE_DATE", -1, null);
        List<Map<String, Object>> rows = listRows(getJson(URI.create(url), LIST_REFERER));
        if (rows == null || rows.isEmpty()) {
            throw new MarketDataException("未能从东财清单接口取到交易日");
        }
        LocalDate date = parseDate(str(rows.get(0).get("TRADE_DATE")));
        if (date == null) {
            throw new MarketDataException("东财清单接口返回的交易日无法解析："
                    + rows.get(0).get("TRADE_DATE"));
        }
        return date;
    }

    /** 最新交易日里总市值前 {@code poolSize} 只。北交所按章程排除。 */
    List<StockRow> fetchListByMarketCap(LocalDate tradeDate) {
        String url = listUrl(LIST_COLUMNS, poolSize, "TOTAL_MARKET_CAP", -1, tradeDate);
        List<Map<String, Object>> items = listRows(getJson(URI.create(url), LIST_REFERER));
        if (items == null) items = List.of();

        List<StockRow> rows = new ArrayList<>();
        for (Map<String, Object> item : items) {
            StockRow row = fromListRow(item);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    /** 一条清单记录 → 只填「谁 / 哪只 / 市值」，其余等行情补齐。 */
    static StockRow fromListRow(Map<String, Object> item) {
        String code = str(item.get("SECURITY_CODE"));
        if (code == null) return null;
        String secucode = str(item.get("SECUCODE"));
        String market = secucode == null || !secucode.contains(".")
                ? null : secucode.substring(secucode.indexOf('.') + 1).toUpperCase();
        // 只留沪深：北交所流动性与口径都另说，章程里明确不纳入。
        if (!"SH".equals(market) && !"SZ".equals(market)) return null;

        StockRow row = new StockRow();
        row.setCode(code);
        row.setName(str(item.get("SECURITY_NAME_ABBR")));
        row.setMarket("SH".equals(market) ? 1 : 0);
        row.setTotalMarketCap(dec(item.get("TOTAL_MARKET_CAP")));
        return row;
    }

    // ==================================================================
    // 行情批量（ulist）
    // ==================================================================

    /**
     * 按 secid 批量取行情。分批发，每批 {@code screener.quote-batch-size} 只。
     *
     * <p>一旦识别出被限流就**立刻抛出、不再发剩下的批次**——把余下的批次打出去，
     * 换不来数据，只会让封禁更深。
     *
     * @return 代码 → 行情，可能不完整（个别批次失败时缺几只）
     * @throws MarketDataException.MarketDataRateLimitedException 被限流
     */
    public Map<String, StockRow> fetchQuotes(List<String> secids) {
        Map<String, StockRow> out = new LinkedHashMap<>();
        if (secids == null || secids.isEmpty()) return out;

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < secids.size(); i += quoteBatchSize) {
            List<String> batch = secids.subList(i, Math.min(i + quoteBatchSize, secids.size()));
            try {
                List<Map<String, Object>> diff = diffOf(getJson(ulistUri(batch), QUOTE_REFERER));
                if (diff == null) {
                    failures.add("第 " + (i / quoteBatchSize + 1) + " 批返回结构异常");
                    continue;
                }
                for (Map<String, Object> item : diff) {
                    StockRow row = toRow(item);
                    if (row == null) continue;
                    row.setEtf(false);
                    out.put(row.getCode(), row);
                }
            } catch (MarketDataException.MarketDataRateLimitedException e) {
                throw e;
            } catch (RuntimeException e) {
                failures.add("第 " + (i / quoteBatchSize + 1) + " 批：" + shortReason(e));
            }
        }
        if (out.isEmpty() && !failures.isEmpty()) {
            throw new MarketDataException("行情批量接口全部失败—— " + String.join("；", failures));
        }
        if (!failures.isEmpty()) {
            log.warn("部分行情批次失败，池子里会少掉这些标的：{}", failures);
        }
        return out;
    }

    /** 拼 ETF 批量行情 URL。{@code secids} 里的逗号与点号必须原样保留。 */
    static URI ulistUri(List<String> secids) {
        return URI.create(UriComponentsBuilder.fromHttpUrl(QUOTE_HOST + ULIST_PATH)
                .queryParam("fltt", 2)
                .queryParam("invt", 2)
                .queryParam("ut", UT)
                .queryParam("secids", String.join(",", secids))
                .queryParam("fields", QUOTE_FIELDS)
                .build(true)
                .toUriString());
    }

    // ==================================================================
    // 指数基金批量行情
    // ==================================================================

    /** 单只指数基金的行情（可能缺失）。 */
    public record EtfQuote(String code, String name, BigDecimal price, BigDecimal pctChange,
                           BigDecimal amount, BigDecimal marketCap, BigDecimal peTtm, BigDecimal pb) {}

    /**
     * 批量取 7 只宽基 ETF 的行情。**尽力而为**：失败返回空 Map，
     * 上层仍会用硬编码的池子 + 日线出价格位置，不会因此整页失败。
     *
     * <p>这里刻意**不**抛限流异常：指数块是整页里可有可无的一块，
     * 为了它把已经算好的个股结果整个丢掉不划算。限流信号由个股日线那一步负责上报。
     */
    public Map<String, EtfQuote> fetchEtfQuotes(List<String> secids) {
        Map<String, EtfQuote> out = new LinkedHashMap<>();
        if (secids == null || secids.isEmpty()) return out;
        try {
            List<Map<String, Object>> diff = diffOf(getJson(ulistUri(secids), QUOTE_REFERER));
            if (diff != null) {
                for (Map<String, Object> item : diff) {
                    StockRow row = toRow(item);
                    if (row == null) continue;
                    out.put(row.getCode(), new EtfQuote(row.getCode(), row.getName(), row.getPrice(),
                            row.getPctChange(), row.getAmount(), row.getTotalMarketCap(),
                            row.getPeTtm(), row.getPb()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("ETF 批量行情不可用（{}），指数块将只按日线给价格位置", shortReason(e));
            return out;
        }
        if (out.isEmpty()) {
            log.warn("ETF 批量行情返回空，指数块将只按日线给价格位置");
        }
        return out;
    }

    // ==================================================================
    // 日线
    // ==================================================================

    /**
     * 前复权日线。**必须 fqt=1**：用原始价会在除权日显示假跌 30%，把正常分红股判成「便宜」。
     * 该方法不抛异常，失败信息塞在返回值里，让上层保留基本面分而不是整只淘汰。
     * 唯一的例外是**被限流**：那件事必须让上层知道，好进入冷却。
     */
    public KlineOutcome fetchKline(String secid) {
        String url = UriComponentsBuilder.fromHttpUrl(KLINE_HOST + KLINE_PATH)
                .queryParam("secid", secid)
                .queryParam("klt", 101)
                .queryParam("fqt", 1)
                .queryParam("lmt", klineLimit)
                .queryParam("end", 20500101)
                .queryParam("fields1", "f1,f2,f3,f4,f5,f6")
                .queryParam("fields2", "f51,f52,f53,f54,f55,f56,f57,f58")
                .build().encode().toUriString();
        try {
            Map<String, Object> body = getJson(URI.create(url), QUOTE_REFERER);
            Object data = body == null ? null : body.get("data");
            if (!(data instanceof Map<?, ?> dm)) {
                return KlineOutcome.failed("日线返回结构异常，价格位置未确认");
            }
            Object klines = dm.get("klines");
            if (!(klines instanceof List<?> list)) {
                return KlineOutcome.failed("日线为空，价格位置未确认");
            }
            List<PricePositionCalculator.Bar> bars = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof String line)) continue;
                // "日期,开,收,高,低,量,额,振幅"
                String[] parts = line.split(",");
                if (parts.length < 3) continue;
                BigDecimal close = parseDecimal(parts[2]);
                if (close == null || close.signum() <= 0) continue;
                try {
                    bars.add(new PricePositionCalculator.Bar(LocalDate.parse(parts[0]), close));
                } catch (RuntimeException ignored) {
                    // 单根日期解析失败就跳过，不影响整段
                }
            }
            if (bars.isEmpty()) return KlineOutcome.failed("日线无有效收盘价，价格位置未确认");
            return KlineOutcome.ok(bars);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            return KlineOutcome.throttled("行情源限流，价格位置未能更新");
        } catch (RuntimeException e) {
            return KlineOutcome.failed("日线不可达（" + shortReason(e) + "），价格位置未确认");
        }
    }

    // ==================================================================
    // 传输
    // ==================================================================

    /**
     * 发一次 GET 并把**被限流**翻译成显式异常。
     *
     * <p>判据是「连接建起来了、服务端却一个字节都没回」：{@code NoHttpResponseException}
     * （Apache HttpClient 5 的说法）、被重置的 {@code SocketException}、{@code EOFException}。
     * 这与 {@code ConnectException}（连都连不上）必须分开——后者换域名重试是合理的，
     * 前者重试只是在加深封禁。
     */
    private Map<String, Object> getJson(URI uri, String referer) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Referer", referer);
            headers.set("User-Agent", UA);
            headers.set("Accept", "application/json, text/plain, */*");
            ResponseEntity<Map> resp = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(headers), Map.class);
            //noinspection unchecked
            return resp.getBody();
        } catch (RuntimeException e) {
            if (isThrottled(e)) {
                throw new MarketDataException.MarketDataRateLimitedException(
                        "行情源拒绝了本次请求（" + shortReason(e) + "）", e);
            }
            throw e;
        }
    }

    /** 失败是否说明「服务端在拒绝我们」而不是「这一条请求有问题」。 */
    static boolean isThrottled(Throwable t) {
        Throwable root = rootCause(t);
        if (root instanceof java.net.SocketException) {
            String m = root.getMessage() == null ? "" : root.getMessage().toLowerCase();
            return m.contains("reset") || m.contains("abort") || m.contains("closed")
                    || m.contains("broken pipe");
        }
        if (root instanceof java.io.EOFException) return true;
        // Apache HttpClient 5 的两种说法。按类名判断，免得为了一句分类把
        // 具体 HTTP 客户端绑进这个类的编译期依赖里。
        String name = root.getClass().getSimpleName();
        return "NoHttpResponseException".equals(name)
                || "ConnectionClosedException".equals(name);
    }

    /** 失败是否发生在「TCP 都没建起来」这一层（域名解析不了、连不上、连超时）。 */
    static boolean isConnectLevel(Throwable t) {
        Throwable root = rootCause(t);
        return root instanceof java.net.ConnectException
                || root instanceof java.net.SocketTimeoutException
                || root instanceof java.net.UnknownHostException;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable root = t;
        Set<Throwable> seen = new HashSet<>();
        while (root.getCause() != null && root.getCause() != root && seen.add(root)) {
            root = root.getCause();
        }
        return root;
    }

    /** 去掉协议前缀，报错里只留域名，免得一行几十个字符都是 https://。 */
    static String hostLabel(String host) {
        return host.replaceFirst("^https?://", "");
    }

    /** 取最根因的一句话。RestTemplate 的外层消息会把整条 URL 塞进来，读不出重点。 */
    static String shortReason(Throwable t) {
        Throwable root = rootCause(t);
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : "：" + msg);
    }

    // ==================================================================
    // 解析
    // ==================================================================

    /**
     * datacenter-web 的返回结构是 {@code {"result":{"data":[...],"count":N},"success":bool}}，
     * 与行情接口的 {@code {"data":{"diff":[...]}}} 不是一回事，所以单独一个解析器。
     * 失败时抛异常并带上服务端原话（例如 {@code 参数预处理错误: NoViableAltException}），
     * 这句话是排查 filter 写错的最快线索。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRows(Map<String, Object> body) {
        if (body == null) throw new MarketDataException("东财清单接口没有返回内容");
        Object result = body.get("result");
        if (!(result instanceof Map<?, ?> rm)) {
            Object message = body.get("message");
            throw new MarketDataException("东财清单接口返回异常："
                    + (message == null ? "result 缺失" : message));
        }
        Object data = ((Map<String, Object>) rm).get("data");
        if (!(data instanceof List<?> list)) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> diffOf(Map<String, Object> body) {
        if (body == null) return null;
        Object data = body.get("data");
        if (!(data instanceof Map<?, ?> dm)) return null;
        Object diff = ((Map<String, Object>) dm).get("diff");
        if (diff instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
            return out;
        }
        // 有些镜像会返回以序号为 key 的对象
        if (diff instanceof Map<?, ?> m) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : m.values()) {
                if (o instanceof Map<?, ?> e) out.add((Map<String, Object>) e);
            }
            return out;
        }
        return null;
    }

    static StockRow toRow(Map<String, Object> item) {
        String code = str(item.get("f12"));
        if (code == null || code.isBlank()) return null;
        StockRow row = new StockRow();
        row.setCode(code);
        row.setName(str(item.get("f14")));
        row.setMarket(code.startsWith("6") ? 1 : 0);
        row.setPrice(dec(item.get("f2")));
        row.setPctChange(dec(item.get("f3")));
        row.setAmount(dec(item.get("f6")));
        row.setTurnoverRate(dec(item.get("f8")));
        row.setTotalMarketCap(dec(item.get("f20")));
        row.setFloatMarketCap(dec(item.get("f21")));
        row.setPb(dec(item.get("f23")));
        row.setChange60d(dec(item.get("f24")));
        row.setYtdChange(dec(item.get("f25")));
        row.setListDate(listDate(item.get("f26")));
        row.setRoe(dec(item.get("f37")));
        row.setRevenueGrowth(dec(item.get("f41")));
        row.setProfitGrowth(dec(item.get("f46")));
        row.setGrossMargin(dec(item.get("f49")));
        row.setDebtRatio(dec(item.get("f57")));
        row.setIndustry(str(item.get("f100")));
        row.setBps(dec(item.get("f113")));
        row.setPeTtm(dec(item.get("f115")));
        row.setDividendYield(dec(item.get("f133")));
        return row;
    }

    /** 东财用 "-" 表示缺失。缺失必须是 null，不能变成 0——0 会被当成真实数值参与判断。 */
    static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return parseDecimal(String.valueOf(v));
    }

    static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty() || "-".equals(v) || "--".equals(v)) return null;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "-".equals(s) || "--".equals(s) ? null : s;
    }

    /** f26 是 YYYYMMDD 整数。 */
    static LocalDate listDate(Object v) {
        String s = str(v);
        if (s == null || s.length() < 8) return null;
        try {
            return LocalDate.of(Integer.parseInt(s.substring(0, 4)),
                    Integer.parseInt(s.substring(4, 6)),
                    Integer.parseInt(s.substring(6, 8)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 清单里的日期形如 {@code 2026-09-30 00:00:00}。 */
    static LocalDate parseDate(String s) {
        if (s == null || s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }
}