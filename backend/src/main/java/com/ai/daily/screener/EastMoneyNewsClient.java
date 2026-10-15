package com.ai.daily.screener;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 东财**资讯搜索**（{@code search-api-web.eastmoney.com/search/jsonp}），新闻面的主源。
 *
 * <p>三路源里唯一按**关键词**检索的，所以个股、ETF、指数都能用（公告那路 ETF 恒为空、
 * 政策那路查的是行业）。
 *
 * <h2>探针实测钉死的四条</h2>
 *
 * <ol>
 *   <li><b>{@code param} 只 URL 编码一次。</b>编两次服务端回
 *       {@code {"code":400,"msg":"非法的json格式"}}。这个坑踩上了还算容易发现（它会明确拒绝），
 *       所以没被写成一堆防御代码。</li>
 *   <li><b>查询条件是一个塞进 URL 参数里的 JSON 字符串</b>，而这个 JSON 是**手拼的**，
 *       不走 Jackson——因为要保证中文是**原样的 UTF-8 字节**，不是 {@code 阳} 这样的转义。
 *       探针验证过能用的那一种就是原样 UTF-8；转义后的形态**没人验证过**，
 *       而 Jackson 默认就会把非 ASCII 转义掉。手拼换来的是「打出去的 URL 逐字可断言」。</li>
 *   <li><b>标题带 {@code <em>} 强调标签</b>（{@code preTag}/{@code postTag} 造成的，
 *       例：{@code <em>阳光电源</em>等在内蒙古成立智算科技公司}）。这里**原样保留**，
 *       剥标签是 {@link NewsDigestService} 清洗那一步的活儿——客户端只负责取字段，
 *       清洗集中在一处才不会出现「这一路剥了那一路没剥」。</li>
 *   <li><b>{@code content} 是正文片段，不取</b>（章程 §9.10：不读正文），
 *       连相关性判断都不用它。理由见 §9.10 第 2 条。</li>
 * </ol>
 *
 * <p>被限流时抛 {@link MarketDataException.MarketDataRateLimitedException}，由上层决定冷却。
 */
@Slf4j
@Component
public class EastMoneyNewsClient {

    static final String ORIGIN = "eastmoney-news";
    static final String ORIGIN_LABEL = "东财资讯";

    static final String HOST = "https://search-api-web.eastmoney.com";
    static final String PATH = "/search/jsonp";
    static final String REFERER = "https://so.eastmoney.com/";

    /**
     * 固定的回调名。**用一个我们指定的常量**，不用随机数或时间戳：
     * 剥壳要求「响应以 {@code cb(} 开头」，壳名随机的话那个判据就没法写死，
     * 只能退化成「找第一个左括号」——而那种写法在服务端回 HTML 错误页时会静默解析失败。
     */
    static final String CALLBACK = "cb";

    /** 一次取多少条。最终只输出前 N（章程 §9.10 第 6 条），多取是为了给去重留余量。 */
    static final int PAGE_SIZE = 20;

    static final String TYPE = "cmsArticleWebOld";

    private final RestTemplate restTemplate;

    public EastMoneyNewsClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询条件那个 JSON 字符串。**手拼**，理由见类注释第 2 条。
     *
     * <p>{@code preTag}/{@code postTag} 是服务端给命中词加高亮的标签——就是标题里那些
     * {@code <em>} 的来源。这里照原样请求（探针用的就是这个形态），
     * 不在请求端把它们去掉：去掉之后返回的是否还是同一个结构，没人验证过。
     */
    static String searchParam(String keyword) {
        return "{\"uid\":\"\","
                + "\"keyword\":\"" + jsonEscape(keyword) + "\","
                + "\"type\":[\"" + TYPE + "\"],"
                + "\"client\":\"web\",\"clientType\":\"web\",\"clientVersion\":\"curr\","
                + "\"param\":{\"" + TYPE + "\":{"
                + "\"searchScope\":\"default\",\"sort\":\"default\",\"pageIndex\":1,"
                + "\"pageSize\":" + PAGE_SIZE + ","
                + "\"preTag\":\"<em>\",\"postTag\":\"</em>\"}}}";
    }

    /** 与探针脚本逐字一致的 URL——{@code EastMoneyNewsClientTest} 拿实测那条做等值断言。 */
    static String searchUrl(String keyword) {
        return HOST + PATH + "?cb=" + CALLBACK
                + "&param=" + NewsWire.percentEncode(searchParam(keyword));
    }

    /** 只转义 JSON 字符串里非转义不可的两个字符。引号与反斜杠，其余原样（含中文）。 */
    static String jsonEscape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                // 控制字符按 JSON 规范必须转义。关键词来自标的名称/行业名，
                // 理论上不会有，但拼进 JSON 之前挡一道的成本是零。
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * 按关键词搜资讯。**失败塞在返回值里，不抛**——唯一会抛的还是被限流（章程 §9.7）。
     */
    public NewsSourceResult search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return NewsSourceResult.failed(NewsItem.Category.NEWS, ORIGIN, ORIGIN_LABEL,
                    "资讯搜索需要一个关键词");
        }
        String trimmed = keyword.strip();
        try {
            // 拿**原文**而不是解析结果：这是 JSONP，不是 JSON，借 fetchJson 会在解析那步就抛。
            String body = MarketDataClient.fetchBody(
                    restTemplate, URI.create(searchUrl(trimmed)), REFERER);
            return parse(NewsWire.jsonp(body, CALLBACK));
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("资讯搜索不可达 keyword={}：{}", trimmed, MarketDataClient.shortReason(e));
            return NewsSourceResult.failed(NewsItem.Category.NEWS, ORIGIN, ORIGIN_LABEL,
                    "资讯源不可达（" + MarketDataClient.shortReason(e) + "）");
        }
    }

    /**
     * 解析。数据在 {@code result.cmsArticleWebOld[]}（探针实测）。
     * 字段：{@code date} / {@code title} / {@code url} / {@code mediaName}。
     */
    static NewsSourceResult parse(JsonNode root) {
        JsonNode rows = root.path("result").path(TYPE);
        if (!rows.isArray()) {
            // 服务端回业务错误时 result 会是空对象或 null——那与「搜到 0 条」不是一回事，
            // 必须分开：前者要有人去看，后者是正常结果。
            String msg = root.path("msg").asText("");
            return NewsSourceResult.failed(NewsItem.Category.NEWS, ORIGIN, ORIGIN_LABEL,
                    msg.isEmpty() ? "资讯源返回结构异常（result 里没有 " + TYPE + "）"
                            : "资讯源返回业务错误：" + msg);
        }

        List<NewsItem> items = new ArrayList<>();
        for (JsonNode row : rows) {
            String title = NewsWire.text(row.get("title"));
            String url = NewsWire.text(row.get("url"));
            var day = NewsWire.dayOf(row.get("date"));
            if (title == null || url == null || day == null) {
                continue;
            }
            items.add(new NewsItem(NewsItem.Category.NEWS, ORIGIN, ORIGIN_LABEL,
                    title, NewsWire.text(row.get("mediaName")), day, url, null));
        }
        return NewsSourceResult.ok(NewsItem.Category.NEWS, ORIGIN, ORIGIN_LABEL, items, rows.size());
    }
}
