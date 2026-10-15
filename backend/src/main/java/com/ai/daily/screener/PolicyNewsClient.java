package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * **政策类头条**检索，走 Google News RSS（{@code news.google.com/rss/search}）。
 *
 * <p>这一路存在的唯一理由是章程 §9.12 与 §7 的调和：用户要「未来往哪发展、有没有政策扶持」，
 * 而 §7 不许我们推断未来。调和方式是**只列举事实**——「某日某媒登了这个标题」是可点击复核
 * 的事实，「这个政策对它是利好」是推断，后者不做。于是这一路的产出就是一组标题，
 * 页面把它们连同日期与链接摆出来，让读的人自己去连。
 *
 * <h2>探针实测钉死的四条</h2>
 *
 * <ol>
 *   <li><b>必须带 {@code when:30d}</b>。实测：不带算子回 47 条只有 5 条在 30 天内
 *       （最旧 5066 天前），带上回 7 条、7 条全在窗内。差别是决定性的——
 *       不带的话本地时间窗会把几乎所有条目滤掉，政策栏看起来像「近期没有政策」。</li>
 *   <li><b>{@code pubDate} 是 GMT。</b>在 {@link #publishedOn} 里就换算到
 *       {@code Asia/Shanghai}——晚一步做的话，20:00 GMT 之后的文章会全变成「第二天」，
 *       而章程 §8 要求每个时间都能回答「哪一日」。</li>
 *   <li><b>{@code link} 是 Google 的跳转地址</b>（{@code news.google.com/rss/articles/CBMi...}），
 *       不是发布方原链。这一点**如实照用**：它对复核仍然有效（点开经 Google 跳一次），
 *       而自己去反解真实 URL 是拿一个不稳定的规则换一个好看点的地址。</li>
 *   <li><b>{@code description} 里带 HTML，不取</b>（§9.10：不读正文）。</li>
 * </ol>
 *
 * <h2>外部输入：XXE 必须关掉</h2>
 *
 * <p>RSS 是完全的外来输入。{@link #newDocumentBuilder} 里把 DOCTYPE 直接判为致命错误——
 * 这不是理论风险：一个带 {@code <!DOCTYPE ... SYSTEM "file:///...">} 的响应，
 * 在默认配置下会让解析器去读本地文件并把内容嵌进结果。<b>只要开了外部实体，
 * 一个 RSS 源就能读到服务器上的任意文件</b>，而我们连这个源的内容都不输出（只输出标题）。
 * 关掉它是零成本的。
 *
 * <p>被限流时抛 {@link MarketDataException.MarketDataRateLimitedException}，由上层决定冷却。
 */
@Slf4j
@Component
public class PolicyNewsClient {

    static final String ORIGIN = "google-news";
    static final String ORIGIN_LABEL = "Google 新闻";

    static final String HOST = "https://news.google.com";
    static final String PATH = "/rss/search";
    static final String REFERER = "https://news.google.com/";

    /** 与 §9.10 第 3 条的时间窗一致。写作查询算子交给 Google 过滤，省掉一批注定要扔的条目。 */
    static final int WINDOW_DAYS = 30;

    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final RestTemplate restTemplate;

    public PolicyNewsClient(@Qualifier("marketRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询词。用**行业名**而不是公司名：用户问的是「有没有相关的政策和信息扶持」，
     * 扶持政策是按行业出的，不按公司出。
     */
    static String query(String subject) {
        return subject + " 政策 when:" + WINDOW_DAYS + "d";
    }

    static String searchUrl(String subject) {
        return HOST + PATH + "?q=" + NewsWire.percentEncode(query(subject))
                + "&hl=zh-CN&gl=CN&ceid=CN:zh-Hans";
    }

    /**
     * 按行业名检索政策类头条。
     *
     * <p><b>不做「标题含标的名」的相关性校验</b>——政策标题不含公司名，这是实测的
     * （章程 §9.10 第 2 条那张表）。行业相关性由查询词负责；
     * 「它得像一条政策」这条判据在 {@link NewsDigestService} 里做。
     */
    public NewsSourceResult searchPolicy(String subject) {
        if (subject == null || subject.isBlank()) {
            return NewsSourceResult.failed(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                    "政策检索需要一个检索词");
        }
        String trimmed = subject.strip();
        try {
            String body = MarketDataClient.fetchBody(
                    restTemplate, URI.create(searchUrl(trimmed)), REFERER);
            return parse(body);
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("政策检索不可达 subject={}：{}", trimmed, MarketDataClient.shortReason(e));
            return NewsSourceResult.failed(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                    "政策源不可达（" + MarketDataClient.shortReason(e) + "）");
        }
    }

    static NewsSourceResult parse(String xml) {
        Document doc;
        try {
            doc = newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            // 这里**不能**把解析失败说成「没有政策」：回 HTML 拦截页也是解析失败，
            // 而那种情况状态码是 200。两件事必须能分开（章程 §6：不许把「没取到」写成「没有」）。
            log.warn("政策源返回的不是可解析的 XML：{}", MarketDataClient.shortReason(e));
            return NewsSourceResult.failed(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                    "政策源返回的不是可解析的 RSS（" + MarketDataClient.shortReason(e) + "）");
        }

        // 根元素必须是 rss 或 feed（Atom）。**这不是形式主义**：一个被拦截的响应体
        // 完全可能是**合法 XML**——<html><body>403 Forbidden</body></html> 标签都闭合，
        // 解析器照收不误，于是「页面被拦」会变成「近期没有政策」。
        // 而没有这道判据时，本类注释里那句「回 HTML 拦截页也是解析失败」是**不成立的**。
        Element root = doc.getDocumentElement();
        if (root == null) {
            return NewsSourceResult.failed(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                    "政策源返回的不是 RSS（没有根元素）");
        }
        String rootTag = root.getTagName();
        if (!"rss".equals(rootTag) && !"feed".equals(rootTag)) {
            return NewsSourceResult.failed(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                    "政策源返回的不是 RSS（根元素是 <" + rootTag + ">）");
        }

        NodeList nodes = doc.getElementsByTagName("item");
        List<NewsItem> items = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) {
                continue;
            }
            String title = NewsWire.text(childText(el, "title"));
            String link = NewsWire.text(childText(el, "link"));
            LocalDate day = publishedOn(childText(el, "pubDate"));
            if (title == null || link == null || day == null) {
                continue;
            }
            items.add(new NewsItem(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                    title, NewsWire.text(childText(el, "source")), day, link, null));
        }
        return NewsSourceResult.ok(NewsItem.Category.POLICY, ORIGIN, ORIGIN_LABEL,
                items, nodes.getLength());
    }

    /** 只取**直接子节点**。用 {@code getElementsByTagName} 会连嵌套的同名标签一起捞。 */
    private static String childText(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element el && tag.equals(el.getTagName())) {
                return el.getTextContent();
            }
        }
        return null;
    }

    /**
     * {@code "Thu, 15 Oct 2026 01:06:54 GMT"} → 上海时区的 {@code 2026-10-15}。
     *
     * <p>认不出返回 null（上层丢弃并计数），**不退回「今天」**：编一个日期看起来和真的
     * 一模一样，而章程 §8 要求每个时间都能回答「哪一日」。
     */
    static LocalDate publishedOn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(raw.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(SHANGHAI)
                    .toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 关了 XXE 的解析器。逐条说明为什么每一行都在：
     *
     * <ul>
     *   <li>{@code disallow-doctype-decl=true} —— 有 DOCTYPE 直接判致命错误。
     *       这是最硬的一条：RSS 本来就不该有 DTD，真有就是异常的。</li>
     *   <li>两条 {@code external-*-entities=false} —— 兜底。万一某个实现不认上一条。</li>
     *   <li>{@code expandEntityReferences=false} —— 不展开实体引用。</li>
     *   <li>{@code XIncludeAware=false} —— 不处理 XInclude，那也是一条读文件的路。</li>
     *   <li>关掉对外部 DTD/schema 的加载 —— 否则解析器会为一个声明了 DTD 的文档
     *       去网络上取那个 DTD（SSRF 的一类）。</li>
     * </ul>
     *
     * <p>属性用 {@code setAttribute} 且**吞掉异常**：不同 JDK 对个别属性的支持不一致，
     * 为一条兜底属性让整个源不可用是得不偿失。核心那条 {@code disallow-doctype-decl}
     * 是标准属性，所有实现都有；它没能生效时测试会红。
     */
    static DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setNamespaceAware(false);
        setQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder();
    }

    private static void setQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            log.debug("RSS 解析器不支持属性 {}，跳过（核心的 DOCTYPE 禁用已生效）", feature);
        }
    }
}
