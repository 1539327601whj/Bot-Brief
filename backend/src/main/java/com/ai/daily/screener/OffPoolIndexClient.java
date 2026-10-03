package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 池外指数的估值：中证官网与蛋卷。
 *
 * <p><b>两个源的口径完全不同，绝不可互相顶替</b>（章程 §5.3）。所以本类**只按
 * {@link OffPoolIndexResolver} 给出的路由去对应的那一个源**，
 * 一个源没有数据时**不会**去问另一个：
 * <ul>
 *   <li><b>中证官网</b>（{@code indexCsiDsPe}）回的是**完整日频 PE 历史**（一只约 15 年、
 *       320KB、0.5–1.2 秒），所以 5 年/10 年基线都算得出来，分位由
 *       {@link RollingPercentile} 按滚动窗口现算，与 Python 的
 *       {@code fetch_csindex_pe_history} 同一口径；</li>
 *   <li><b>蛋卷</b>（{@code index_eva/dj}）一次回**全部指数**，但每只**只有当前值**——
 *       真正带历史的是另一支 {@code index_eva/pe_history}，见
 *       {@link #fetchDanjuanHistory(String)}（**周频**快照，约 52 点/年）。
 *       走 {@link #fetch} 这条路仍只有当日值，所以 5 年/10 年两档会标「该口径源不提供」，
 *       而不是留个空或拿中证的数去补；要历史得显式调那一支。</li>
 * </ul>
 *
 * <p>两者都失败不跨源重试。被限流时抛
 * {@link MarketDataException.MarketDataRateLimitedException}；其余失败塞在返回值里。
 */
@Slf4j
@Component
public class OffPoolIndexClient {

    // ------------------------------------------------------------------
    // 中证官网
    // ------------------------------------------------------------------

    static final String CSINDEX_URL =
            "https://www.csindex.com.cn/csindex-home/perf/indexCsiDsPe";
    static final String CSINDEX_REFERER = "https://www.csindex.com.cn/";

    /** 中证官网的 {@code tradeDate} 是**紧凑的 YYYYMMDD**（{@code 20110628}），不是 ISO。 */
    static final Pattern CSINDEX_DAY = Pattern.compile("\\d{8}");

    /** PE 的合理上限，与后端入库校验同值。超出范围按坏数据丢掉，不参与分位。 */
    static final BigDecimal PE_MAX = new BigDecimal("300");

    // ------------------------------------------------------------------
    // 蛋卷
    // ------------------------------------------------------------------

    static final String DANJUAN_URL = "https://danjuanfunds.com/djapi/index_eva/dj";
    static final String DANJUAN_REFERER = "https://danjuanfunds.com/";

    /**
     * 蛋卷的 PE 历史（**周频**快照）。与 {@link #DANJUAN_URL} 是两支不同的接口，
     * 信封也不一样（这支是 {@code data.index_eva_pe_growths}），别把解析混用。
     */
    static final String DANJUAN_HISTORY_URL =
            "https://danjuanfunds.com/djapi/index_eva/pe_history/";

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final RestTemplate restTemplate;

    public OffPoolIndexClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 一个池外指数的估值结果。
     *
     * <p>{@code points} 是**带 PE 的历史序列**。中证系走 {@link #fetch} 就有；
     * 蛋卷走 {@link #fetch} 时是空列表（那一支只回当日值），要历史得另调
     * {@link #fetchDanjuanHistory(String)}。{@code currentPe} 是当下这一天的 PE，两个源都有。
     *
     * <p>{@code percentileMethod} 必须随结果一起给出去：页面要写出用的是哪个口径，
     * 否则「同一只指数两个 PE」无从分辨（章程 §5.3、§8）。
     */
    public record Result(String source, String label, String percentileMethod, String name,
                         BigDecimal currentPe, BigDecimal currentPercentile, LocalDate currentDate,
                         List<RollingPercentile.Point> points, String failureReason, boolean missing) {
        public boolean ok() {
            return failureReason == null;
        }

        /** 这个源有没有历史序列。{@link #fetch} 这条路只有中证系有；蛋卷的历史在
         * {@link #fetchDanjuanHistory(String)} 那条路上。 */
        public boolean hasHistory() {
            return points != null && points.size() > 1;
        }

        /**
         * 源**明确回答**了「我这里没有这个代码」。
         *
         * <p>它和普通失败必须分开，因为下一步完全相反：这条该回 <b>404 —— 换一个代码</b>，
         * 而「不可达」「业务错误」该回 <b>503 —— 等一会儿再试同一个代码</b>。
         * 把两者都说成 404，用户会以为代码写错了；都说成 503，他会一直重试一个不存在的代码。
         */
        public boolean notFound() {
            return missing;
        }

        static Result ok(String source, String label, String method, String name,
                         BigDecimal currentPe, BigDecimal currentPercentile, LocalDate currentDate,
                         List<RollingPercentile.Point> points) {
            return new Result(source, label, method, name, currentPe, currentPercentile,
                    currentDate, List.copyOf(points), null, false);
        }

        static Result failed(String source, String label, String reason) {
            return new Result(source, label, null, null, null, null, null, List.of(), reason, false);
        }

        /** 源说了「没有这个代码」。见 {@link #notFound()}。 */
        static Result notFound(String source, String label, String reason) {
            return new Result(source, label, null, null, null, null, null, List.of(), reason, true);
        }
    }

    /** 按路由取一个池外指数的估值。**只打路由指定的那一个域名。** */
    public Result fetch(OffPoolIndexResolver.Route route) {
        return switch (route.source()) {
            case CSINDEX -> fetchCsindex(route);
            case DANJUAN -> fetchDanjuan(route);
        };
    }

    // ------------------------------------------------------------------
    // 中证官网
    // ------------------------------------------------------------------

    private Result fetchCsindex(OffPoolIndexResolver.Route route) {
        String url = CSINDEX_URL + "?indexCode=" + route.sourceCode();
        try {
            Map<String, Object> body =
                    MarketDataClient.fetchJson(restTemplate, URI.create(url), CSINDEX_REFERER);
            // 业务信封：code=200 且 success=true。不看它的话，业务错误会被当成「0 个数据点」，
            // 于是页面说「该指数没有历史」——而真实原因可能是参数错了。
            if (!"200".equals(String.valueOf(body.get("code"))) || !Boolean.TRUE.equals(body.get("success"))) {
                return Result.failed(IndexFundPool.SOURCE_CSINDEX, route.label(),
                        "中证官网业务错误（code=" + body.get("code") + "），该指数估值未确认");
            }
            List<RollingPercentile.Point> points = csindexPoints(body.get("data"));
            if (points.isEmpty()) {
                // **notFound 而不是 failed**：请求成功、信封正常、data 是空的——源明确回答了
                // 「我这里没有这个代码」。这正是「把代码写错了」的样子，该回 404。
                return Result.notFound(IndexFundPool.SOURCE_CSINDEX, route.label(),
                        "中证官网没有 " + route.sourceCode() + " 的 PE 历史"
                                + "（该源不覆盖深证系代码，如 399xxx）");
            }
            RollingPercentile.Point last = points.get(points.size() - 1);
            // 分位现算，用的是与 Python 逐点对齐的那一个实现（RollingPercentileTest 守着）
            BigDecimal percentile = RollingPercentile.latest(points).orElse(null);
            return Result.ok(IndexFundPool.SOURCE_CSINDEX, route.label(),
                    IndexFundPool.METHOD_CSINDEX_ROLLING_10Y, null,
                    last.value(), percentile, last.date(), points);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;   // 上层进冷却 → 429；绝不换源重试
        } catch (RuntimeException e) {
            log.warn("中证官网不可达 code={}：{}", route.sourceCode(), MarketDataClient.shortReason(e));
            return Result.failed(IndexFundPool.SOURCE_CSINDEX, route.label(),
                    "中证官网不可达（" + MarketDataClient.shortReason(e) + "），该指数估值未确认");
        }
    }

    /**
     * 解析中证官网的 {@code data} 数组。
     *
     * <p>字段名是 {@code peg}——不是 {@code pe}，也不是 {@code peTtm}。这是实测钉死的，
     * 改名不会有任何报错，只会一夜之间所有中证系指数都「没有历史」。
     */
    static List<RollingPercentile.Point> csindexPoints(Object data) {
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        LocalDate today = LocalDate.now(BEIJING);
        Map<LocalDate, BigDecimal> byDate = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            LocalDate day = csindexDay(m.get("tradeDate"));
            BigDecimal pe = MarketDataClient.dec(m.get("peg"));
            // 与 Python 侧逐条对齐：日期形态不对、PE 缺失/非正/超上限、未来日期，一律丢掉。
            // 未来日期确实会出现（中证官网提前挂出当日行），留着会让「今天」变成一个未来日。
            if (day == null || pe == null || pe.signum() <= 0 || pe.compareTo(PE_MAX) > 0
                    || day.isAfter(today)) {
                continue;
            }
            byDate.put(day, pe);
        }
        List<RollingPercentile.Point> points = new ArrayList<>();
        byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> points.add(new RollingPercentile.Point(e.getKey(), e.getValue())));
        return points;
    }

    /** {@code "20110628"} → {@code 2011-06-28}。形态不认识返回 null，**不猜**。 */
    static LocalDate csindexDay(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (!CSINDEX_DAY.matcher(text).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 蛋卷
    // ------------------------------------------------------------------

    private Result fetchDanjuan(OffPoolIndexResolver.Route route) {
        try {
            Map<String, Object> body =
                    MarketDataClient.fetchJson(restTemplate, URI.create(DANJUAN_URL), DANJUAN_REFERER);
            Map<String, Map<String, Object>> items = danjuanItems(body);
            Map<String, Object> item = items.get(route.sourceCode().toUpperCase(java.util.Locale.ROOT));
            if (item == null) {
                // 与中证那边同理：蛋卷一次回了**全部**指数，清单里没有就是没有这个代码。
                return Result.notFound(IndexFundPool.SOURCE_DANJUAN, route.label(),
                        "蛋卷的 " + items.size() + " 个指数里没有 " + route.sourceCode());
            }
            BigDecimal pe = MarketDataClient.dec(item.get("pe"));
            LocalDate day = danjuanDay(item);
            String name = text(item.get("name"));
            if (pe == null || pe.signum() <= 0 || day == null) {
                // pe=0 在蛋卷意味着「没有数据」（实测确实有这种条目），不是「PE 是 0」
                return Result.failed(IndexFundPool.SOURCE_DANJUAN, route.label(),
                        "蛋卷的 " + route.sourceCode() + " 没有可用的 PE 或日期");
            }
            // **刻意只给一个点**：这支只回当日值。塞一个假的单点序列进 points
            // 会让调用方以为「有历史但很短」，而真相是「这条路没带历史」——
            // 两者在页面上是不同的话。所以 points 留空，由 hasHistory() 为 false 表达。
            // 蛋卷的**周频 PE 历史**在另一支 index_eva/pe_history，见 fetchDanjuanHistory。
            return Result.ok(IndexFundPool.SOURCE_DANJUAN, route.label(),
                    IndexFundPool.METHOD_DANJUAN, name, pe, danjuanPercentile(item), day, List.of());
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("蛋卷不可达 code={}：{}", route.sourceCode(), MarketDataClient.shortReason(e));
            return Result.failed(IndexFundPool.SOURCE_DANJUAN, route.label(),
                    "蛋卷不可达（" + MarketDataClient.shortReason(e) + "），该指数估值未确认");
        }
    }

    /**
     * 蛋卷某个指数的 **PE 历史**（周频快照，约 52 点/年；实测 516 点覆盖 2016-09 ~ 2026-09）。
     *
     * <h2>它和 {@link #fetchDanjuan} 是**两支不同的接口**</h2>
     *
     * <p>{@code djapi/index_eva/dj} 一次回全部 63 个指数、但只有**当日**值；
     * 这一支按指数要历史，信封也不同（{@code data.index_eva_pe_growths}，
     * 不是 {@code result_code} + {@code data.items}），所以 {@code danjuanItems} 不能复用。
     *
     * <p><b>每条只有 {@code pe} 和 {@code ts}，没有分位</b>——分位由调用方用项目自己的
     * {@link RollingPercentile} 现算。实测这与蛋卷自家的 {@code pe_percentile} 是同一个口径
     * （对 {@code SZ399006} 的 516 个周频点现算得 28.29%，蛋卷报 27.48%，差 0.8pp，
     * 方向也一致：越高越贵）。所以拿它算出来的分位可以继续叫
     * {@link IndexFundPool#METHOD_DANJUAN}，不需要另立一个口径。
     *
     * <p>失败语义：不是每个指数都有历史（实测 {@code SZ399005} 回的是**空数组**），
     * 而且**乱填的代码也回空数组**——两者分不开，所以这条永远不返回 {@link Result#notFound()}：
     * 那会让调用方回 404，用户以为代码写错了。
     *
     * @throws MarketDataException.MarketDataRateLimitedException 被蛋卷拒绝。与
     *         {@link #fetchCsindex}、{@link #fetchDanjuan} 一致：上层 429、不换源重试。
     */
    public Result fetchDanjuanHistory(String indexCode) {
        String code = indexCode == null ? "" : indexCode.trim();
        String url = DANJUAN_HISTORY_URL + code + "?day=all";
        try {
            Map<String, Object> body =
                    MarketDataClient.fetchJson(restTemplate, URI.create(url), DANJUAN_REFERER);
            // **先看业务码**：day 给错时回 {"result_code":999001,"message":"参数错误"} 且**没有 data**。
            // 不看它就会把「参数错误」读成「这个指数没有历史」——两句在页面上是完全不同的话。
            String resultCode = body == null ? null : String.valueOf(body.getOrDefault("result_code", "0"));
            if (body == null || (!"0".equals(resultCode) && !"200".equals(resultCode))) {
                return Result.failed(IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                        "蛋卷 PE 历史业务错误（result_code=" + resultCode + " "
                                + (body == null ? "" : body.getOrDefault("message", "")) + "）");
            }
            List<RollingPercentile.Point> points = danjuanHistoryPoints(body.get("data"));
            if (points.size() <= 1) {
                return Result.failed(IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                        "蛋卷没有 " + code + " 的 PE 历史（该源不是每个指数都有）");
            }
            RollingPercentile.Point last = points.get(points.size() - 1);
            return Result.ok(IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                    IndexFundPool.METHOD_DANJUAN, null,
                    last.value(), RollingPercentile.latest(points).orElse(null), last.date(), points);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("蛋卷 PE 历史不可达 code={}：{}", code, MarketDataClient.shortReason(e));
            return Result.failed(IndexFundPool.SOURCE_DANJUAN, OffPoolIndexResolver.LABEL_DANJUAN,
                    "蛋卷 PE 历史不可达（" + MarketDataClient.shortReason(e) + "）");
        }
    }

    /**
     * {@code data.index_eva_pe_growths} → 升序的 PE 序列。
     *
     * <p>逐条与 {@link #csindexPoints} 同一个写法、同一个尺度，免得两个源的清洗习惯分叉：
     * 日期形态不认识、PE 缺失/非正/超上限、未来日期一律丢掉。蛋卷的 {@code pe=0}
     * 是「那天没有数据」而不是「PE 是 0」，留着会把整条曲线拉低。同一天出现两条时后者覆盖
     * （蛋卷偶尔会给当天补一条）。单点或空表由调用方统一当「没有历史」。
     */
    @SuppressWarnings("unchecked")
    static List<RollingPercentile.Point> danjuanHistoryPoints(Object data) {
        if (!(data instanceof Map<?, ?> dm)) {
            return List.of();
        }
        Object raw = ((Map<String, Object>) dm).get("index_eva_pe_growths");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        LocalDate today = LocalDate.now(BEIJING);
        Map<LocalDate, BigDecimal> byDate = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) m;
            LocalDate day = danjuanDay(row);
            BigDecimal pe = MarketDataClient.dec(row.get("pe"));
            if (day == null || pe == null || pe.signum() <= 0 || pe.compareTo(PE_MAX) > 0
                    || day.isAfter(today)) {
                continue;
            }
            byDate.put(day, pe);
        }
        List<RollingPercentile.Point> points = new ArrayList<>();
        byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> points.add(new RollingPercentile.Point(e.getKey(), e.getValue())));
        return points;
    }

    /**
     * {@code data.items} 按 {@code index_code} 大写归集。一次请求回**全部指数**，
     * 所以池外指数扩容几乎不额外花钱——但也正因为一次回全部，**不要**按指数逐个调它。
     */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> danjuanItems(Map<String, Object> body) {
        // 业务码：result_code 是字符串或数字，两种都见过
        String code = body == null ? null : String.valueOf(body.getOrDefault("result_code", "0"));
        if (body == null || (!"0".equals(code) && !"200".equals(code))) {
            throw new MarketDataException("蛋卷估值业务错误：result_code=" + code
                    + " " + (body == null ? "" : body.getOrDefault("result_msg", "")));
        }
        Object data = body.get("data");
        if (!(data instanceof Map<?, ?> dm)) {
            return Map.of();
        }
        Object items = dm.get("items");
        if (!(items instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object indexCode = m.get("index_code");
            if (indexCode == null || String.valueOf(indexCode).isBlank()) {
                continue;
            }
            byCode.put(String.valueOf(indexCode).trim().toUpperCase(java.util.Locale.ROOT),
                    (Map<String, Object>) m);
        }
        return byCode;
    }

    /**
     * 蛋卷的日期：先试 {@code ts}（epoch **毫秒**，北京时间），退回 {@code date} 字段。
     *
     * <p>两处都不能省：{@code ts} 是主要来源，但实测有条目只有 {@code date}；
     * 而 {@code date} 的形态不认识时必须返回 null —— 猜日期等于把一个未知日说成已知。
     */
    static LocalDate danjuanDay(Map<String, Object> item) {
        Object ts = item.get("ts");
        if (ts instanceof Number n) {
            try {
                return Instant.ofEpochMilli(n.longValue()).atZone(BEIJING).toLocalDate();
            } catch (RuntimeException ignored) {
                // 落到下面的 date 字段
            }
        }
        String raw = text(item.get("date"));
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw, ISO_DAY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 蛋卷的 {@code pe_percentile} 是 0–1 的比值，要用得乘 100。超出这个范围视为缺失。 */
    static BigDecimal danjuanPercentile(Map<String, Object> item) {
        BigDecimal raw = MarketDataClient.dec(item.get("pe_percentile"));
        if (raw == null || raw.signum() < 0 || raw.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        return raw.multiply(BigDecimal.valueOf(100));
    }

    private static String text(Object raw) {
        String s = raw == null ? null : String.valueOf(raw).trim();
        return s == null || s.isEmpty() ? null : s;
    }
}