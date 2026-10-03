package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 个股 PE(TTM) 的**日频历史**，来自东财「估值分析」（{@code data.eastmoney.com/gzfx/detail/{code}.html}）。
 *
 * <p><b>为什么需要它。</b>行情接口的 {@code f115} 只有「当下这一个 PE」，没有序列，
 * 于是算不出分位——这正是章程 §7 原来写着「个股没有 PE 历史分位」的原因。估值分析是
 * **逐日快照**表，按股票代码过滤就能拿到整段历史，分位才有得算。
 *
 * <p><b>为什么它是主源而不是 f115。</b>
 * <ul>
 *   <li>只有它有历史；</li>
 *   <li>它在 {@code datacenter-web.eastmoney.com} —— 与按 IP 限流的 {@code push2} 是
 *       <b>两个域名</b>。{@code MarketDataClient} 的注释记着这个域名「抗封禁」，
 *       所以查 PE 历史不会跟行情抢配额。</li>
 * </ul>
 *
 * <p><b>代价，必须让页面写出来：</b>它与 {@code f115} 是**两份快照**，同一天的值可能不等。
 * 所以 {@code f115} 只能当降级源，且用它的时候页面上要写明「PE 来自备用口径，与分位不同源」。
 * 拿两个源的数拼成一条线，正是章程 §5.3 禁止的跨口径比较。
 *
 * <p><b>三个已经实测钉死的细节</b>（探源脚本 {@code verify_lookup_sources.py} 守着）：
 * <ol>
 *   <li>{@code filter} 里的代码**不能加引号**：{@code filter=(SECURITY_CODE=300274)} 对，
 *       {@code filter=(SECURITY_CODE="300274")} 永远拿不到数据（HTTP 400，或更糟——
 *       静默 200 回 0 行）。同一个接口上 {@code TRADE_DATE} 反而**必须**带引号，
 *       所以这对参数的写法不是「统一加引号」能覆盖的。</li>
 *   <li>{@code TRADE_DATE} 是 {@code "YYYY-MM-DD HH:MM:SS"}，前 10 位才是交易日。</li>
 *   <li>服务端按 {@code sortTypes=-1} 返回**降序**，但这里不依赖它——自己排一遍。
 *       依赖服务端顺序的地方，某天对方改了默认排序就会静默给出反向的基线。</li>
 * </ol>
 *
 * <p><b>只打这一个域名，绝不跨域名重试</b>，被限流时抛
 * {@link MarketDataException.MarketDataRateLimitedException} 交给上层进冷却（→ HTTP 429）。
 * 其余失败塞在返回值里，不抛。
 */
@Slf4j
@Component
public class StockValuationClient {

    static final String HOST = "https://datacenter-web.eastmoney.com";
    static final String PATH = "/api/data/v1/get";
    static final String REFERER = "https://data.eastmoney.com/";
    static final String REPORT = "RPT_VALUEANALYSIS_DET";

    /**
     * 只点这几列。刻意**不要** {@code columns=ALL}——整段历史最小列约 176KB，
     * ALL 会多带十几个用不上的列，把响应放大四倍，而这里一次点击就要拉十年。
     *
     * <p>{@code PE_LAR}（最近年报口径）也在列表里，但**不是我们用的那个**：
     * 它与 {@code PE_TTM} 在同一个接口上同时存在，取错了不会有任何报错，
     * 只是分位从此偏一点。列在 {@link #COLUMNS} 里是为了能在测试中断言没取错，
     * 解析时只读 {@code PE_TTM}。
     */
    static final String COLUMNS =
            "SECURITY_CODE,SECUCODE,SECURITY_NAME_ABBR,TRADE_DATE,PE_TTM,PE_LAR,PB_MRQ,CLOSE_PRICE";

    /** 一只票的表级历史约 2100 行（约 8.7 年）。5000 一次拿满是刻意的：分页会让「十年」落在第二页。 */
    static final int PAGE_SIZE = 5000;

    /**
     * 个股 PE 分位的口径名。
     *
     * <p>与 {@link IndexFundPool#METHOD_CSINDEX_ROLLING_10Y} 一样，方法名承载的是
     * **算法**（滚动 {@code min(10年, 可用长度)} 窗口的「小于等于」占比），来源另说；
     * 但个股这条**不进库**（本页查完即走，不落库），所以它不在
     * {@code MarketValuationHistoryServiceImpl.SUPPORTED_PERCENTILE_METHODS} 白名单里——
     * 往白名单里加会让人以为库里能查到它。
     *
     * <p>{@code VALUEANALYSIS} 而不是 {@code EM}：同一个东财域名下还有 {@code push2} 的
     * {@code f115}，那是**另一份快照**（只有当日值）。两者混用会出现「同一只票两个 PE」，
     * 方法名必须能把它们分开。
     */
    public static final String METHOD_STOCK_VALUATION_ROLLING_10Y =
            "EM_VALUEANALYSIS_PE_TTM_ROLLING_10Y";

    /**
     * 这个源是**按股票代码**取的，代码会被拼进 {@code filter}。
     * 拼之前必须确认它真的是 6 位数字——不校验就等于把查询串交给调用方拼。
     */
    static final Pattern STOCK_CODE = Pattern.compile("\\d{6}");

    private final RestTemplate restTemplate;

    public StockValuationClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 一天的估值观测。{@code peTtm} 为 null = 那天没有 PE（亏损或未披露），**不是 0**。 */
    public record Point(LocalDate tradeDate, BigDecimal peTtm, BigDecimal pbMrq, BigDecimal closePrice) {}

    /**
     * 拼查询 URL。<b>刻意手拼、不走 {@code UriComponentsBuilder}</b>，理由与
     * {@link MarketDataClient#listUrl} 逐字相同：{@code filter} 由服务端用 ANTLR 解析，
     * 且**不做 URL 解码**——`=` 一旦被编码成 `%3D`，服务端直接回
     * {@code 参数预处理错误: NoViableAltException}。
     *
     * <p>这不是理论风险：第一版就是用 {@code UriComponentsBuilder...build().encode()} 写的，
     * 它把 `=` 编码掉了。{@code StockValuationClientTest} 里那条断言请求原文的用例把它抓了出来——
     * 而如果没抓出来，线上表现会是「查个股永远没有 PE 分位」，看起来像源本身没数据。
     *
     * <p>注意 {@code code} 在 {@code filter} 里**不带引号**，而同一个接口按 {@code TRADE_DATE}
     * 过滤时**必须**带单引号（见 {@link MarketDataClient#listUrl}）。两种写法并存不是笔误。
     */
    static String historyUrl(String code) {
        StringBuilder sb = new StringBuilder(HOST).append(PATH).append('?');
        sb.append("reportName=").append(REPORT);
        sb.append("&columns=").append(COLUMNS);
        sb.append("&pageSize=").append(PAGE_SIZE);
        sb.append("&pageNumber=1");
        sb.append("&sortColumns=TRADE_DATE&sortTypes=-1");
        sb.append("&filter=(SECURITY_CODE=").append(code).append(')');
        sb.append("&source=WEB&client=WEB");
        return sb.toString();
    }

    /**
     * 一只票的整段 PE 历史。
     *
     * <p>{@code rawCount} 是服务端**回了多少行**（交易日读不出来的行在
     * {@link #parse} 里就被丢掉了，不计入这里，也不在 {@code points} 里）；
     * {@code points} 是其中有可辨认交易日的行，<b>含 PE 为 null 的那些</b>——
     * 它们的 PB 与收盘价仍然可用，所以留着。有多少天没有 PE 由
     * {@link #daysWithoutPe()} 单独回答，见那里的说明。
     */
    public record History(String code, String name, List<Point> points, int rawCount,
                          String failureReason) {
        public boolean ok() {
            return failureReason == null;
        }

        /**
         * 有几天没有可用的 PE（亏损或未披露）。
         *
         * <p>这些点**留在 {@code points} 里**（PB、收盘价还有用），但会被排除在 PE 窗口之外，
         * 所以这个数必须单独说出来：少掉的那几天直接改变分位的分母，
         * 而整条分位曲线只会偏一点点——页面上永远看不出来（章程 §6）。
         *
         * <p><b>不能拿 {@code rawCount - points.size()} 来算。</b>那个差是「交易日字段读不出来的行」，
         * 与「这天没有 PE」是两回事：前者是坏数据，后者是这家公司当天真的没有 PE。
         * 把两者混成一个数，页面上的理由就会指错方向。
         */
        public int daysWithoutPe() {
            int missing = 0;
            for (Point p : points) {
                if (p.peTtm() == null) {
                    missing++;
                }
            }
            return missing;
        }

        static History ok(String code, String name, List<Point> points, int rawCount) {
            return new History(code, name, List.copyOf(points), rawCount, null);
        }

        static History failed(String code, String reason) {
            return new History(code, null, List.of(), 0, reason);
        }
    }

    /**
     * 取一只票的 PE 历史，**升序**返回。
     *
     * @param code 6 位股票代码
     * @return 失败信息在返回值里；唯一会抛的是被限流
     */
    public History fetchHistory(String code) {
        if (code == null || !STOCK_CODE.matcher(code).matches()) {
            return History.failed(code, "个股代码必须是 6 位数字");
        }
        String url = historyUrl(code);
        try {
            Map<String, Object> body =
                    MarketDataClient.fetchJson(restTemplate, URI.create(url), REFERER);
            List<Map<String, Object>> rows = resultRows(body);
            if (rows.isEmpty()) {
                return History.failed(code, "估值分析源没有这只票的记录（代码是否正确，或该票是否已退市）");
            }
            return parse(code, rows);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            // 原样抛出：上层要进冷却并回 429。**绝不换域名重试。**
            throw e;
        } catch (RuntimeException e) {
            log.warn("个股估值历史不可达 code={}：{}", code, MarketDataClient.shortReason(e));
            return History.failed(code, "估值分析源不可达（" + MarketDataClient.shortReason(e) + "），PE 分位未确认");
        }
    }

    private History parse(String code, List<Map<String, Object>> rows) {
        String name = null;
        // 同一天出现两行时以**后面**那行为准（服务端是降序，即取较早出现的那条）；
        // 用 LinkedHashMap 去重而不是 List，免得同一天被数两次——分位的窗口会因此变长
        Map<LocalDate, Point> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate day = tradeDate(row.get("TRADE_DATE"));
            if (day == null) {
                continue;
            }
            if (name == null) {
                name = text(row.get("SECURITY_NAME_ABBR"));
            }
            BigDecimal pe = MarketDataClient.dec(row.get("PE_TTM"));
            byDate.put(day, new Point(day, pe,
                    MarketDataClient.dec(row.get("PB_MRQ")),
                    MarketDataClient.dec(row.get("CLOSE_PRICE"))));
        }
        List<Point> points = new ArrayList<>(byDate.values());
        // 自己排，不依赖服务端的 sortTypes：依赖它的话，对方改默认排序就会静默给出反向基线
        points.sort(Comparator.comparing(Point::tradeDate));
        // 没有 PE 的行留着（PB 与收盘价还是可用的），但上层要知道它们会让窗口少几天
        return History.ok(code, name, points, rows.size());
    }

    /** {@code "2018-01-02 00:00:00"} → {@code 2018-01-02}。格式不认识就返回 null，不猜。 */
    static LocalDate tradeDate(Object raw) {
        String text = text(raw);
        if (text == null || text.length() < 10 || text.charAt(4) != '-') {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(Object raw) {
        String s = raw == null ? null : String.valueOf(raw).trim();
        return s == null || s.isEmpty() ? null : s;
    }

    /** 数据在 {@code result.data}。{@code result} 缺失说明服务端回了业务错误，按 0 行处理。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> resultRows(Map<String, Object> body) {
        Object result = body == null ? null : body.get("result");
        if (!(result instanceof Map<?, ?> rm)) {
            return List.of();
        }
        Object data = rm.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                rows.add((Map<String, Object>) m);
            }
        }
        return rows;
    }
}