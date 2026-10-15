package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 东财**法定披露公告**（{@code np-anotice-stock.eastmoney.com/api/security/ann}）。
 *
 * <p>这是三路源里权重最高的一路（章程 §9.10 第 5 条：法定披露 &gt; 权威财经媒体 &gt; 门户转载）：
 * 公告是上市公司自己发的，不是媒体转述的。
 *
 * <h2>探针实测钉死的三条</h2>
 *
 * <ol>
 *   <li><b>日期取 {@code notice_date}，不碰 {@code display_time}。</b>
 *       实测同一批公告里两者**只有 9/10 条同日**——含义不同（前者公告日，后者入库时间）。
 *       更要命的是 {@code display_time} 长这样：{@code 2026-10-15 16:02:03:301}，
 *       毫秒位用**冒号**分隔，不是标准 {@code LocalDateTime} 格式，
 *       直接反序列化会在**运行时、且只在有公告的票上**抛异常。</li>
 *   <li><b>ETF 代码恒为空。</b>{@code stock_list=510300} 回
 *       {@code {"data":{"list":[],"total_hits":0},"success":1}}——字段名是对的、
 *       接口认这个代码，是**真空**。基金公告不从这条路出。这一条**不是失败**，
 *       按 §9.11 如实呈现「本次取到 0 条」，空卡片比换接口硬凑诚实。</li>
 *   <li><b>{@code columns[].column_name} 是公告分类</b>（实测出现过「股份质押、冻结」）。
 *       它把章程 §9.12 说的「筹码与预期没有数据源」改成**有一半是有出处的**：
 *       质押/解禁/减持这类可以原样列出标题+日期+链接，只是**不判断它的影响**。</li>
 * </ol>
 *
 * <h2>详情链接是构造出来的，且只验证到「可达」</h2>
 *
 * <p>响应里**没有**现成的 URL 字段（{@code art_code} 是唯一能定位一条公告的东西），
 * 所以链接必须自己拼。拼出来的形态实测**可达**（HTTP 200），但
 * {@code data.eastmoney.com/notices/detail/} 是个 **JS 壳**——真 art_code 和假 art_code
 * 回的是**逐字相同的 200 与相同长度**的页面，服务端不按 art_code 区分内容，
 * 靠客户端 JS 读路径再拉正文。所以：
 * <b>「链接打得开」是验证过的，「打开的是不是这一条」没有验证过。</b>
 * 这一点写在这里，是为了下次有人发现链接指错时，能直接看到「这是已知的、没验到的那一段」，
 * 而不是从头再查一遍。
 *
 * <p>被限流时抛 {@link MarketDataException.MarketDataRateLimitedException}，交给上层决定
 * 进哪个源的冷却——**本类不自己进冷却**，因为它不知道调用方还在跑哪几路源。
 */
@Slf4j
@Component
public class StockNoticeClient {

    /** provider 键。刻意**不与 {@code eastmoney} 合键**：一次公告限流不该锁死价格主链（章程 §9.9）。 */
    static final String ORIGIN = "eastmoney-notice";
    static final String ORIGIN_LABEL = "东财公告";

    static final String HOST = "https://np-anotice-stock.eastmoney.com";
    static final String PATH = "/api/security/ann";
    static final String REFERER = "https://data.eastmoney.com/notices/";

    /**
     * 一次取多少条。取 20 是因为**时间窗在管线那一层过滤**（公告放宽到 90 天，§9.10 第 3 条）：
     * 一只安静的公司最近 20 条可能横跨一年，被窗口滤掉后剩不下几条——那时页面上要如实写
     * 「源回了 20 条，落在 90 天内的 0 条」。**不翻页**：每源只发一次是 §9.8 的硬纪律。
     */
    static final int PAGE_SIZE = 20;

    /** {@code A} = 全部类型。探针用的就是它。 */
    static final String ANN_TYPE = "A";

    static final Pattern CODE = Pattern.compile("[0-9A-Za-z]{1,12}");

    /**
     * 公告分类里命中这些词的，属于章程 §9.12 说的「筹码与预期」**有出处的**那一半。
     * 这只是**打标签**用的——命中的公告会被标出来，但页面不据此下任何判断。
     */
    static final List<String> HOLDING_WORDS = List.of(
            "质押", "冻结", "解禁", "减持", "增持", "诉讼", "仲裁", "处罚", "激励", "回购");

    private final RestTemplate restTemplate;

    public StockNoticeClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询 URL。手拼，与 {@link StockValuationClient#historyUrl} 同理——
     * 这类接口的参数形态是实测定的，中间插一层 URL 构造器就可能悄悄改变编码。
     */
    static String url(String code) {
        return HOST + PATH
                + "?sr=-1"
                + "&page_size=" + PAGE_SIZE
                + "&page_index=1"
                + "&ann_type=" + ANN_TYPE
                + "&client_source=web"
                + "&stock_list=" + NewsWire.percentEncode(code);
    }

    /**
     * 公告详情页。**构造的**——响应里没有 URL 字段，见类注释里那段「只验证到可达」。
     *
     * @param code    市场归属用，取该条自带的 {@code codes[].stock_code}
     * @param artCode 公告唯一编号
     */
    static String detailUrl(String code, String artCode) {
        return "https://data.eastmoney.com/notices/detail/" + code + "/" + artCode + ".html";
    }

    /**
     * 取一只标的的公告。**失败塞在返回值里，不抛**——唯一会抛的还是被限流（章程 §9.7）。
     */
    public NewsSourceResult fetchNotices(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return NewsSourceResult.failed(NewsItem.Category.NOTICE, ORIGIN, ORIGIN_LABEL,
                    "公告源只接受数字或字母代码");
        }
        try {
            Map<String, Object> body =
                    MarketDataClient.fetchJson(restTemplate, URI.create(url(code)), REFERER);
            return parse(code, body);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("公告源不可达 code={}：{}", code, MarketDataClient.shortReason(e));
            return NewsSourceResult.failed(NewsItem.Category.NOTICE, ORIGIN, ORIGIN_LABEL,
                    "公告源不可达（" + MarketDataClient.shortReason(e) + "）");
        }
    }

    @SuppressWarnings("unchecked")
    static NewsSourceResult parse(String code, Map<String, Object> body) {
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof Map<?, ?> dm)) {
            // 结构异常与「没有公告」必须分开：前者要有人去看，后者是正常结果。
            return NewsSourceResult.failed(NewsItem.Category.NOTICE, ORIGIN, ORIGIN_LABEL,
                    "公告源返回结构异常（没有 data 段）");
        }
        Object raw = dm.get("list");
        if (!(raw instanceof List<?> list)) {
            return NewsSourceResult.failed(NewsItem.Category.NOTICE, ORIGIN, ORIGIN_LABEL,
                    "公告源返回结构异常（data 里没有 list）");
        }

        List<NewsItem> items = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> rm)) {
                continue;
            }
            NewsItem item = one((Map<String, Object>) rm, code);
            if (item != null) {
                items.add(item);
            }
        }
        // 空列表是**正常结果**（ETF 恒为空，见类注释），不是失败。
        return NewsSourceResult.ok(NewsItem.Category.NOTICE, ORIGIN, ORIGIN_LABEL, items, list.size());
    }

    /** 一条公告。缺标题 / 缺日期 / 缺 art_code 的都返回 null——由调用方计入「源回了几条」。 */
    @SuppressWarnings("unchecked")
    private static NewsItem one(Map<String, Object> row, String queriedCode) {
        String title = NewsWire.text(row.get("title"));
        if (title == null) {
            title = NewsWire.text(row.get("title_ch"));
        }
        String artCode = NewsWire.text(row.get("art_code"));
        var day = NewsWire.dayOf(row.get("notice_date"));
        if (title == null || artCode == null || day == null) {
            return null;
        }
        // 详情页路径里那个代码优先用**这一条自带的**：查询代码与公告归属代码通常相同，
        // 但同一份公告挂多个代码时（例如 A+H），自带的那个才是这条公告真正的归属。
        String linkCode = ownCode(row, queriedCode);
        return new NewsItem(NewsItem.Category.NOTICE, ORIGIN, ORIGIN_LABEL,
                title, null, day, detailUrl(linkCode, artCode), categoryOf(row));
    }

    @SuppressWarnings("unchecked")
    private static String ownCode(Map<String, Object> row, String fallback) {
        Object codes = row.get("codes");
        if (codes instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            String own = NewsWire.text(((Map<String, Object>) first).get("stock_code"));
            if (own != null) {
                return own;
            }
        }
        return fallback;
    }

    /**
     * 把 {@code columns[].column_name} 拼成一个分类标签（可能多个，用顿号连）。
     * 没有就是 null——**不编一个「其他」**，页面上空着比写个假的强。
     */
    @SuppressWarnings("unchecked")
    private static String categoryOf(Map<String, Object> row) {
        Object columns = row.get("columns");
        if (!(columns instanceof List<?> list)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (Object c : list) {
            if (c instanceof Map<?, ?> cm) {
                String name = NewsWire.text(((Map<String, Object>) cm).get("column_name"));
                if (name != null && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 这条公告的分类是否落在「筹码与预期」的有出处那一半（章程 §9.12）。
     * 只用于给条目打标签，**不参与任何判定**。
     */
    static boolean isHoldingRelated(String category) {
        if (category == null) {
            return false;
        }
        return HOLDING_WORDS.stream().anyMatch(category::contains);
    }
}
