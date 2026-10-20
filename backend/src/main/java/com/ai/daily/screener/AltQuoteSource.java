package com.ai.daily.screener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 兜底行情源：腾讯 + 新浪。**只在主源（东财）拿不到时用**。
 *
 * <h2>为什么不塞进 {@link MarketDataClient}</h2>
 *
 * <p>那个类的注释整篇在讲一件事：<b>东财按 IP 限流、行情域名只留一个、失败不跨域名重试</b>。
 * 把「换一个公司再试一次」的逻辑混进去，会让那个契约当场失真——读代码的人会以为
 * 东财那条线也开始跨域名重试了。反过来这里也刻意**不模仿**东财的重试纪律：
 * 这三个是不同的公司，各自的配额互不相干，一个挂了换另一个正是这里的全部意义。
 *
 * <h2>charset 与东财相反，这是新增的乱码风险点</h2>
 *
 * <p>{@link MarketDataClient#charsetOf} 的默认值是 UTF-8（RFC 8259 规定 JSON 必须是 UTF-8）。
 * 腾讯与新浪**反着来**：它们不声明 charset，内容是 GBK。所以这里**不 sniff、不读响应头，
 * 一律强制 GBK**。默认值取错的后果和东财那边一样隐蔽——GBK 的中文按 UTF-8 解出来是乱码，
 * 而乱码照样能被后面的字符串解析吃下去（结构字符全是 ASCII），
 * 于是「沪深300ETF华泰柏瑞」变成「娌狪娣?300ETF…」，字段齐全、一个都不少、不报任何错。
 * 所以这个默认值必须在解码的那一行就定死，而不是指望响应头写对。
 *
 * <h2>实测字段位置（2026-09-30 现场数过，别照记忆改）</h2>
 *
 * <p>腾讯 {@code qt.gtimg.cn/q=sh510300} 回 {@code v_sh510300="…"}，{@code ~} 分隔共 <b>88</b> 个字段：
 * {@code [1]}名称 {@code [2]}代码 {@code [3]}现价 {@code [4]}昨收 {@code [5]}今开
 * {@code [32]}涨跌幅% {@code [37]}成交额<b>万元</b> {@code [45]}总市值<b>亿</b>。
 * 两位位置是用算术核对过的，不是看名字猜的：{@code [32]}=0.36 而
 * {@code ([3]-[4])/[4]}=(4.432-4.416)/4.416=0.3623% ——对得上，说明 [4] 确实是昨收。
 * <b>ETF 没有 PE/PB</b>（{@code [39]} 实测为空），所以从那两个字段来的值一律 null，不编。
 *
 * <p>新浪 {@code hq.sinajs.cn/list=sh510300} 回 {@code var hq_str_sh510300="…"}，逗号分隔共 <b>34</b> 个字段：
 * {@code [0]}名称 {@code [1]}今开 {@code [2]}昨收 {@code [3]}现价 {@code [4]}最高 {@code [5]}最低
 * {@code [8]}成交量(股) {@code [9]}成交额<b>元</b> {@code [30]}日期 {@code [31]}时间。
 * 同样对过账：{@code [3]}=4.432 == 腾讯 {@code [3]}；{@code [9]}=2196229027 元 == 腾讯 {@code [37]} 219623 万 ×10⁴。
 *
 * <p>新浪<b>只给价与成交额</b>：没有规模字段，也没有 PE。为了一个「基金规模」在点击时翻十几页
 * 新浪 ETF 清单不值得，所以规模那格留 null 并写清原因，而不是随手补一个别的数。
 *
 * <p>两个源对「不认识的代码」的表示方式**不同**，解析时都要当缺失：
 * 腾讯是<b>整条语句都不回</b>（写错市场前缀的代码静默消失），新浪是回一个空串
 * {@code var hq_str_xx000000="";}。前者尤其容易骗人——看着像「请求成功但没数据」。
 */
@Slf4j
@Component
public class AltQuoteSource {

    /** 这两个名字进冷却表与页面的「行情来源」两行，必须唯一且稳定。 */
    public static final String PROVIDER_TENCENT = "tencent";
    public static final String PROVIDER_SINA = "sina";

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 只取我们点名的字段，多给的（这两个源偶尔会加列）不该让解析失败——
     * 与 {@link MarketDataClient} 里那份 MAPPER 同一个理由。
     */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static final String TENCENT_QUOTE_URL = "https://qt.gtimg.cn/q=";
    static final String TENCENT_REFERER = "https://gu.qq.com/";
    static final String TENCENT_KLINE_URL = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    /**
     * 主域名的备用入口。实测：{@code web.ifzq.gtimg.cn} 对机房 IP 有时回 WAF 的 HTML 页，
     * 而同一个 {@code param} 打到 {@code proxy.finance.qq.com} 能正常返回 JSON。
     * Python 侧的日报脚本早就这么做了（{@code automation/scripts/etf_report.py}）。
     *
     * <p>只有**传输/解析异常**才换到这里；JSON 有效但没有日线、或者被限流，都不换——
     * 那两种情况下换域名只会多打一次同一个封禁面。
     */
    static final String TENCENT_KLINE_PROXY_URL =
            "https://proxy.finance.qq.com/ifzqgtimg/appstock/app/fqkline/get";
    static final String TENCENT_KLINE_REFERER = "https://gu.qq.com/";
    static final String SINA_QUOTE_URL = "https://hq.sinajs.cn/list=";
    static final String SINA_REFERER = "https://finance.sina.com.cn/";

    /** 一批几十只。两个源都支持逗号批量，批太大只是把单条请求变成一条容易超时的长 URL。 */
    static final int MAX_BATCH = 50;

    /**
     * 腾讯日线一次能要的**最大根数**——实测的硬上限，不是随便挑的数。
     *
     * <p>请求 800 → 800 根；请求 900/1500/2000 → **不报错**，悄悄掉回默认页只给 640 根
     * （比报错更坏：分位会静默变短）；请求 2600 → {@code {"msg":"param error","data":[]}}
     * 直接拒绝。要更长只能按日期区间翻页，见
     * {@link #fetchTencentKline(String, LocalDate, int)}。
     *
     * <p>夹取必须发生在**代码里**：配置写错时（比如把上限调到 2000）它不会报错，
     * 只会让每一页都悄悄变成 640 根，而页面上看不出少的是什么。
     */
    public static final int TENCENT_KLINE_MAX_ROWS = 800;

    static final int TENCENT_NAME = 1;
    /**
     * {@code [2]} 是代码。**解析时不读它**——语句名 {@code v_sh510300} 里的那个才是权威的，
     * 从 payload 里再读一遍只是给自己留一个「两个来源不一致时听谁的」的问题。
     * 留着这个常量是因为它是那份 88 字段布局的一部分，测试造夹具时按它摆位，
     * 免得夹具和真实响应在这些不读的位置上悄悄分叉。
     */
    static final int TENCENT_CODE = 2;
    static final int TENCENT_PRICE = 3;
    static final int TENCENT_PREV_CLOSE = 4;
    static final int TENCENT_PCT_CHANGE = 32;
    static final int TENCENT_AMOUNT_WAN = 37;
    static final int TENCENT_MARKET_CAP_YI = 45;
    /** 实测的字段总数。对不上就说明位置会漂，见 {@link #parseTencentQuotes}。 */
    static final int TENCENT_FIELD_COUNT = 88;

    static final int SINA_NAME = 0;
    static final int SINA_PREV_CLOSE = 2;
    static final int SINA_PRICE = 3;
    static final int SINA_AMOUNT = 9;

    static final BigDecimal WAN = BigDecimal.valueOf(10_000L);
    static final BigDecimal YI = BigDecimal.valueOf(100_000_000L);

    private final RestTemplate restTemplate;
    /**
     * 日线补数用短超时模板（连接 2s / 读 4s）。报价仍走 {@link #restTemplate}：
     * 那是首屏必等的一跳，但只有一次；日线补数可能翻好几页，必须单独压上界。
     */
    private final RestTemplate klineRestTemplate;

    @Autowired
    public AltQuoteSource(@Qualifier("marketRestTemplate") RestTemplate restTemplate,
                          @Qualifier("lookupFallbackRestTemplate") RestTemplate klineRestTemplate) {
        this.restTemplate = restTemplate;
        this.klineRestTemplate = klineRestTemplate;
    }

    /** 测试与旧调用点保留的单参构造：两个用途共用同一个模板。 */
    public AltQuoteSource(RestTemplate restTemplate) {
        this(restTemplate, restTemplate);
    }

    // ==================================================================
    // 腾讯
    // ==================================================================

    /**
     * 腾讯批量行情。**尽力而为**，与 {@link MarketDataClient#fetchEtfQuotes} 一致：
     * 缺的标的就不在返回的 Map 里，上层按缺失写原因串，而不是整块失败。
     *
     * @throws MarketDataException.MarketDataRateLimitedException 被腾讯拒绝。调用方据此
     *         记 {@link ScreenerCache#enterCooldown(String)}——**只记腾讯那一路**。
     */
    public Map<String, MarketDataClient.EtfQuote> fetchTencentQuotes(List<String> codes) {
        return fetchTencentQuotesBySymbols(symbolsOf(codes, "腾讯行情"));
    }

    /**
     * 按**显式符号**取腾讯行情（{@code sh600519} / {@code sz300274}）。
     *
     * <p>与 {@link #fetchTencentQuotes(List)} 的区别只有一处：符号由调用方判好，
     * 不经过 {@link #tencentSymbol(String)}。那条老映射刻意只认基金段（5/1 开头），
     * 因为它是给 ETF 块用的；v2 的行情兜底要覆盖个股，再把个股塞进那条映射里
     * 会让 v1 的「判断不了就跳过」失去意义。
     */
    public Map<String, MarketDataClient.EtfQuote> fetchTencentQuotesBySymbols(List<String> symbols) {
        Map<String, MarketDataClient.EtfQuote> out = new LinkedHashMap<>();
        symbols = validSymbols(symbols);
        for (int i = 0; i < symbols.size(); i += MAX_BATCH) {
            List<String> batch = symbols.subList(i, Math.min(i + MAX_BATCH, symbols.size()));
            // 逗号必须原样出现在查询串里，所以这里直接拼字符串，不走 UriComponentsBuilder 的编码。
            String body = getDecoded(URI.create(TENCENT_QUOTE_URL + String.join(",", batch)),
                    TENCENT_REFERER, gbk());
            parseTencentQuotes(body, out);
        }
        if (out.isEmpty() && !symbols.isEmpty()) {
            log.warn("腾讯行情没有回任何一只（请求了 {} 只）", symbols.size());
        }
        return out;
    }

    /**
     * 腾讯日线（前复权）。形状与东财的 {@code klines} 不同但字段位置一致，
     * 所以走同一份 {@link MarketDataClient#parseDelimitedBars}——**不许在这边重写一套解析**。
     *
     * <p>不抛异常，失败塞在返回值里，与 {@link MarketDataClient#fetchKline} 的约定一致。
     */
    public MarketDataClient.KlineOutcome fetchTencentKline(String code, int limit) {
        return fetchTencentKline(code, null, limit);
    }

    /**
     * 同上，但只取 {@code end}（含）**之前**的那一批。
     *
     * <p>存在的理由是 {@code count} 的上限只有 800（≈3.3 年），而「代码查询」要看十年。
     * 实测：只给 {@code end}、不给 {@code start} 时，返回的是**该区间末尾的 800 根**；
     * 于是拿上一页最早的那天减一天当新的 {@code end}，就能一页页往回翻，且
     * **页与页之间不重不漏**（实测 5 页 3596 根、零重复日期、连续无洞）。
     * <b>翻几页、什么时候放弃是调用方的策略</b>，这里只管一次请求。
     *
     * @param end 取的最后一个交易日（含）。为 null 时 param 串与
     *            {@link #fetchTencentKline(String, int)} **逐字相同**——这有测试钉着，
     *            因为那一串的逗号与空档位是接口格式的一部分。
     */
    public MarketDataClient.KlineOutcome fetchTencentKline(String code, LocalDate end, int limit) {
        String symbol = tencentSymbol(code);
        if (symbol == null) {
            return MarketDataClient.KlineOutcome.failed(
                    "腾讯日线不认这个代码的市场前缀", PROVIDER_TENCENT);
        }
        return tencentKlineOutcome(symbol, end, limit);
    }

    /**
     * 按**显式符号**取腾讯日线（{@code sh600519} / {@code sz300274}）。
     *
     * <p>{@link #fetchTencentKline(String, LocalDate, int)} 只能表示基金代码；
     * v2 要把个股日线也落本地，符号由调用方判好后走这里。
     */
    public MarketDataClient.KlineOutcome fetchTencentKlineBySymbol(String symbol, LocalDate end,
                                                                   int limit) {
        if (!isSymbol(symbol)) {
            return MarketDataClient.KlineOutcome.failed(
                    "腾讯日线符号不是 sh/sz+6 位：" + symbol, PROVIDER_TENCENT);
        }
        return tencentKlineOutcome(symbol, end, limit);
    }

    /**
     * 腾讯日线，**保留完整 OHLC**。给「点击时补齐 ETF 日线」用：
     * 收盘价那版（{@link #fetchTencentKline}）只够算价格位置，落库后图表与 ATR 都没了。
     *
     * <p>失败语义与 {@link MarketDataClient#fetchKlineRows} 一致：不抛异常，
     * `throttled` 直接告诉调用方进冷却。
     */
    public MarketDataClient.KlineRowsOutcome fetchTencentKlineRows(String code, LocalDate end,
                                                                   int limit) {
        String symbol = tencentSymbol(code);
        if (symbol == null) {
            return MarketDataClient.KlineRowsOutcome.failed(
                    "腾讯日线不认这个代码的市场前缀", PROVIDER_TENCENT);
        }
        return tencentKlineRowsOutcome(symbol, end, limit);
    }

    /**
     * 一次腾讯日线请求的**唯一**出口：URL 拼装、解析、失败映射都只写这一份。
     *
     * <p>限流那条 catch 尤其不能复制——它一旦出现第二份，「被限流翻译成 throttled」这件事
     * 就有两个可能不一致的版本，而两边都只体现在文案上，没人看得出。
     */
    private MarketDataClient.KlineOutcome tencentKlineOutcome(String symbol, LocalDate end, int limit) {
        MarketDataClient.KlineRowsOutcome rows = tencentKlineRowsOutcome(symbol, end, limit);
        if (!rows.ok()) {
            return rows.throttled()
                    ? MarketDataClient.KlineOutcome.throttled(
                    "兜底行情源限流，价格位置未能更新", PROVIDER_TENCENT)
                    : MarketDataClient.KlineOutcome.failed(rows.failureReason(), PROVIDER_TENCENT);
        }
        // 收盘价路径从同一份 KlineRow 里取 close：两边的「有效行」判据必须一致，
        // 否则会出现「落库有 800 根、价格位置却只认 790 根」。
        List<PricePositionCalculator.Bar> bars = new ArrayList<>(rows.rows().size());
        for (MarketDataClient.KlineRow row : rows.rows()) {
            bars.add(new PricePositionCalculator.Bar(row.tradeDate(), row.closePrice()));
        }
        if (bars.isEmpty()) {
            return MarketDataClient.KlineOutcome.failed(
                    "腾讯日线无有效收盘价，价格位置未确认", PROVIDER_TENCENT);
        }
        return MarketDataClient.KlineOutcome.ok(bars, PROVIDER_TENCENT);
    }

    private MarketDataClient.KlineRowsOutcome tencentKlineRowsOutcome(String symbol, LocalDate end,
                                                                      int limit) {
        try {
            List<?> raw = requestTencentKlineRows(symbol, end, limit);
            List<MarketDataClient.KlineRow> rows = MarketDataClient.parseKlineRows(raw);
            if (rows.isEmpty()) {
                return MarketDataClient.KlineRowsOutcome.failed(
                        "腾讯日线无有效收盘价，价格位置未确认", PROVIDER_TENCENT);
            }
            return MarketDataClient.KlineRowsOutcome.ok(rows, PROVIDER_TENCENT);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            return MarketDataClient.KlineRowsOutcome.throttled(
                    "兜底行情源限流，价格位置未能更新", PROVIDER_TENCENT);
        } catch (RuntimeException e) {
            return MarketDataClient.KlineRowsOutcome.failed(
                    "腾讯日线不可达（" + MarketDataClient.shortReason(e) + "），价格位置未确认",
                    PROVIDER_TENCENT);
        }
    }

    /**
     * 主域名 → 备用域名的唯一换点。
     *
     * <p>换的条件只有「传输 / HTTP / 解析异常」：JSON 有效但没有日线（这只没有数据）、
     * 或者被限流，都是**确定性的回答**，换域名只会多打一次同一个封禁面。
     */
    private List<?> requestTencentKlineRows(String symbol, LocalDate end, int limit) {
        // param=sh510300,day,,,250,qfq —— 逗号与空档位都是格式的一部分，必须原样保留。
        // end 落在第 4 个档位：param,day,start,end,count,qfq
        // 800 是腾讯的单页硬上限：请求 900/1500/2000 会被悄悄截成 640，2600 直接 param error。
        int count = Math.min(800, Math.max(1, limit));
        String param = symbol + ",day,," + (end == null ? "" : end) + "," + count + ",qfq";
        try {
            return requestTencentKlineRows(TENCENT_KLINE_URL, param, symbol);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;
        } catch (RuntimeException primary) {
            log.warn("腾讯日线主域名失败，改走备用域名 symbol={}：{}",
                    symbol, MarketDataClient.shortReason(primary));
            try {
                return requestTencentKlineRows(TENCENT_KLINE_PROXY_URL, param, symbol);
            } catch (MarketDataException.MarketDataRateLimitedException e) {
                throw e;
            } catch (RuntimeException secondary) {
                secondary.addSuppressed(primary);
                throw secondary;
            }
        }
    }

    private List<?> requestTencentKlineRows(String baseUrl, String param, String symbol) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("param", param)
                .build(true).toUriString();
        String body = getDecoded(URI.create(url), TENCENT_KLINE_REFERER, gbk(), klineRestTemplate);
        return tencentKlineRows(body, symbol);
    }

    /**
     * 腾讯 {@code v_sym="…"} 语句 → {@code EtfQuote}。
     *
     * <p>单位要换算成与东财一致（成交额、市值都是<b>元</b>）：腾讯给的是
     * {@code [37]} 万元、{@code [45]} 亿元，不换算就会让同一个字段在两个源之间差一万倍，
     * 而页面上看不出是单位错了。
     *
     * <p><b>字段总数对不上时，位置相关的字段一律按缺失处理。</b>腾讯加一列就会让
     * {@code [37]}/{@code [45]} 指向别的数——那是「读到了值、但是错的值」，比 null 危险得多
     * （章程里缺失不许变成 0，同理也不许变成别人）。只有 {@code [1]–[5]} 这段（名称/价/昨收）
     * 是加了列也不会动的，所以只保留它们。
     */
    static void parseTencentQuotes(String body, Map<String, MarketDataClient.EtfQuote> out) {
        if (body == null) return;
        for (String statement : body.split(";")) {
            int eq = statement.indexOf('=');
            if (eq < 0) continue;
            String key = statement.substring(0, eq).trim();
            // 响应里还会有 v_pv_none_match 之类的东西，名字不是 v_<市场><代码> 的一律跳过
            if (!key.startsWith("v_")) continue;
            String symbol = key.substring(2);
            if (!isSymbol(symbol)) continue;
            String payload = unquote(statement.substring(eq + 1));
            // 腾讯对不认识的代码是**整条语句都不回**；空了当缺失，不猜
            if (payload.isEmpty()) continue;

            String[] f = payload.split("~", -1);
            BigDecimal price = MarketDataClient.parseDecimal(at(f, TENCENT_PRICE));
            if (price == null || price.signum() <= 0) continue;

            boolean positionsTrustworthy = f.length == TENCENT_FIELD_COUNT;
            if (!positionsTrustworthy) {
                log.warn("腾讯行情字段数 {} != {}，涨跌幅/成交额/市值的位置不再可信，本轮按缺失处理：{}",
                        f.length, TENCENT_FIELD_COUNT, symbol);
            }
            String code = symbol.substring(2);
            out.put(code, new MarketDataClient.EtfQuote(
                    code,
                    MarketDataClient.str(at(f, TENCENT_NAME)),
                    price,
                    positionsTrustworthy
                            ? MarketDataClient.parseDecimal(at(f, TENCENT_PCT_CHANGE)) : null,
                    positionsTrustworthy
                            ? scaled(at(f, TENCENT_AMOUNT_WAN), WAN) : null,
                    positionsTrustworthy
                            ? scaled(at(f, TENCENT_MARKET_CAP_YI), YI) : null,
                    // ETF 没有 PE/PB，不编
                    null, null,
                    PROVIDER_TENCENT));
        }
    }

    /** 从腾讯的 {@code data.<sym>.qfqday} 里取日线行。 */
    @SuppressWarnings("unchecked")
    static List<?> tencentKlineRows(String body, String symbol) {
        if (body == null || body.isBlank()) return List.of();
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new MarketDataException(
                    "腾讯日线返回的不是 JSON：" + MarketDataClient.snippet(body), e);
        }
        Object data = root.get("data");
        if (!(data instanceof Map<?, ?> dm)) return List.of();
        Object node = ((Map<String, Object>) dm).get(symbol);
        if (!(node instanceof Map<?, ?> nm)) return List.of();
        // 带 qfq 参数时是 qfqday；个别标的不给复权序列，那时只有 day。两个都认，
        // 但**不**退而求其次去拿不复权的序列拼进同一根曲线——那正是复权口径混用。
        Object rows = ((Map<String, Object>) nm).get("qfqday");
        if (!(rows instanceof List<?>)) rows = ((Map<String, Object>) nm).get("day");
        return rows instanceof List<?> list ? list : List.of();
    }

    // ==================================================================
    // 新浪
    // ==================================================================

    /**
     * 新浪批量行情。**只给价与成交额**，规模与 PE 一律 null（见类注释）。
     * 上限用途与腾讯那路完全一样：主源缺哪几只就补哪几只。
     *
     * @throws MarketDataException.MarketDataRateLimitedException 被新浪拒绝，调用方据此
     *         记 {@link ScreenerCache#enterCooldown(String)}。
     */
    public Map<String, MarketDataClient.EtfQuote> fetchSinaQuotes(List<String> codes) {
        return fetchSinaQuotesBySymbols(symbolsOf(codes, "新浪行情"));
    }

    /** 按显式符号取新浪行情。理由见 {@link #fetchTencentQuotesBySymbols(List)}。 */
    public Map<String, MarketDataClient.EtfQuote> fetchSinaQuotesBySymbols(List<String> symbols) {
        Map<String, MarketDataClient.EtfQuote> out = new LinkedHashMap<>();
        symbols = validSymbols(symbols);
        for (int i = 0; i < symbols.size(); i += MAX_BATCH) {
            List<String> batch = symbols.subList(i, Math.min(i + MAX_BATCH, symbols.size()));
            String body = getDecoded(URI.create(SINA_QUOTE_URL + String.join(",", batch)),
                    SINA_REFERER, gbk());
            parseSinaQuotes(body, out);
        }
        if (out.isEmpty() && !symbols.isEmpty()) {
            log.warn("新浪行情没有回任何一只（请求了 {} 只）", symbols.size());
        }
        return out;
    }

    /** 新浪 {@code var hq_str_sym="…"} → {@code EtfQuote}。 */
    static void parseSinaQuotes(String body, Map<String, MarketDataClient.EtfQuote> out) {
        if (body == null) return;
        for (String statement : body.split(";")) {
            int marker = statement.indexOf("hq_str_");
            int eq = statement.indexOf('=', marker < 0 ? 0 : marker);
            if (marker < 0 || eq < 0) continue;
            String symbol = statement.substring(marker + "hq_str_".length(), eq).trim();
            if (!isSymbol(symbol)) continue;
            String payload = unquote(statement.substring(eq + 1));
            // 新浪对不认识的代码回空串——与腾讯「整条不回」不同，但同样是缺失
            if (payload.isEmpty()) continue;

            String[] f = payload.split(",", -1);
            BigDecimal price = MarketDataClient.parseDecimal(at(f, SINA_PRICE));
            if (price == null || price.signum() <= 0) continue;

            String code = symbol.substring(2);
            out.put(code, new MarketDataClient.EtfQuote(
                    code,
                    MarketDataClient.str(at(f, SINA_NAME)),
                    price,
                    derivedPctChange(price, MarketDataClient.parseDecimal(at(f, SINA_PREV_CLOSE))),
                    MarketDataClient.parseDecimal(at(f, SINA_AMOUNT)),
                    // 新浪不给规模，也不给 PE。留 null 并把原因写进页面的状态串，
                    // 不为了「不留空」去翻那十几页清单，更不拿别的数顶上。
                    null, null, null,
                    PROVIDER_SINA));
        }
    }

    /**
     * 新浪不给涨跌幅，用它自己报的现价与昨收算一个。
     *
     * <p>这不是「编」：两个输入都是它给的数，算式是唯一确定的。但昨收缺失或为 0 时
     * 必须返回 null——除以 0 得不到数，而随便给个 0 会被当成「今天没涨没跌」读进页面。
     */
    static BigDecimal derivedPctChange(BigDecimal price, BigDecimal prevClose) {
        if (price == null || prevClose == null || prevClose.signum() == 0) return null;
        return price.subtract(prevClose)
                .multiply(BigDecimal.valueOf(100L))
                .divide(prevClose, 2, RoundingMode.HALF_UP);
    }

    // ==================================================================
    // 传输
    // ==================================================================

    /**
     * 取一次文本。**字符集由调用方指定，绝不从响应头推**——这两个源是不声明 charset 的 GBK，
     * 而推断的兜底默认是 UTF-8（见类注释）。
     *
     * <p>仍然先读 {@code byte[]}：所有解码这一步都得我们自己说了算。
     */
    private String getDecoded(URI uri, String referer, Charset charset) {
        return getDecoded(uri, referer, charset, restTemplate);
    }

    private String getDecoded(URI uri, String referer, Charset charset, RestTemplate template) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Referer", referer);
            headers.set("User-Agent", UA);
            ResponseEntity<byte[]> resp = template.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(headers), byte[].class);
            byte[] raw = resp.getBody();
            return raw == null ? null : new String(raw, charset);
        } catch (RuntimeException e) {
            if (MarketDataClient.isThrottled(e)) {
                throw new MarketDataException.MarketDataRateLimitedException(
                        "兜底行情源拒绝了本次请求（" + MarketDataClient.shortReason(e) + "）", e);
            }
            throw e;
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 两个源都是 GBK。写成一个方法是为了让「强制、不推断」这件事只有一个出处。 */
    static Charset gbk() {
        return Charset.forName("GBK");
    }

    /**
     * 6 位基金代码 → 带市场前缀的符号。用交易所的编码段判断，不是猜：
     * 沪市基金是 5 开头、深市基金是 1 开头。判断不了的返回 null 并记一行日志——
     * **不能默认给个 sh**，前缀写错的后果是两个源都静默丢代码（腾讯尤其），
     * 表现为「这一只就是没有行情」而看不出原因。
     */
    static String tencentSymbol(String code) {
        if (code == null) return null;
        String c = code.trim();
        if (c.length() != 6) return null;
        if (c.startsWith("5")) return "sh" + c;
        if (c.startsWith("1")) return "sz" + c;
        return null;
    }

    private static List<String> symbolsOf(List<String> codes, String what) {
        List<String> symbols = new ArrayList<>();
        if (codes == null) return symbols;
        for (String code : codes) {
            String symbol = tencentSymbol(code);
            if (symbol == null) {
                log.warn("{} 判断不出这个代码的市场前缀，已跳过：{}", what, code);
                continue;
            }
            symbols.add(symbol);
        }
        return symbols;
    }

    private static boolean isSymbol(String symbol) {
        return symbol != null && symbol.length() == 8
                && (symbol.startsWith("sh") || symbol.startsWith("sz"));
    }

    /**
     * 只留下 {sh,sz}+6 位的符号。
     *
     * <p>显式符号入口也要过这一道：调用方判错时（比如把中证代码拼成 sh930782）
     * 宁可不发请求，也不要让源回一堆认不出的语句、再被当成「源说没有这只标的」。
     */
    private static List<String> validSymbols(List<String> symbols) {
        List<String> out = new ArrayList<>();
        if (symbols == null) return out;
        for (String symbol : symbols) {
            if (isSymbol(symbol)) {
                out.add(symbol);
            } else {
                log.warn("行情符号不是 sh/sz+6 位，已跳过：{}", symbol);
            }
        }
        return out;
    }

    /** 越界一律 null：字段数比预期的少时，按缺失处理比读到一个不存在的位置好。 */
    private static String at(String[] fields, int index) {
        return fields != null && index >= 0 && index < fields.length ? fields[index] : null;
    }

    /** 值 × 单位。值为空则结果为空——缺的仍然是缺的，不会变成 0。 */
    private static BigDecimal scaled(String raw, BigDecimal unit) {
        BigDecimal value = MarketDataClient.parseDecimal(raw);
        return value == null ? null : value.multiply(unit);
    }

    /** 去掉可选的双引号。两个源都写成 {@code KEY="值";}。 */
    private static String unquote(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        return s;
    }
}