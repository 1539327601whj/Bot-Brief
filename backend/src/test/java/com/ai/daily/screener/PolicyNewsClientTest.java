package com.ai.daily.screener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.EOFException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PolicyNewsClient}。三处重点：{@code when:30d} 必须带上、GMT 必须换到上海时区、
 * 外部实体必须读不出来。
 */
class PolicyNewsClientTest {

    private static final String RSS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>"电源设备 政策" - Google 新闻</title>
              <link>https://news.google.com/rss/search?q=x</link>
              <description>频道自身的描述，不该被当成条目</description>
              <item>
                <title>国家能源局印发新型储能发展行动方案</title>
                <link>https://news.google.com/rss/articles/CBMiAAA1</link>
                <pubDate>Thu, 15 Oct 2026 01:06:54 GMT</pubDate>
                <source url="https://www.nea.gov.cn">国家能源局</source>
                <description>&lt;a href="x"&gt;正文片段&lt;/a&gt;不该出现在结果里</description>
              </item>
              <item>
                <title>多地出台分布式光伏补贴细则</title>
                <link>https://news.google.com/rss/articles/CBMiAAA2</link>
                <pubDate>Thu, 15 Oct 2026 20:00:00 GMT</pubDate>
                <source url="https://www.gov.cn">中国政府网</source>
                <description>正文片段</description>
              </item>
            </channel></rss>
            """;

    private RestTemplate restTemplate;
    private PolicyNewsClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new PolicyNewsClient(restTemplate);
    }

    private void respondsWith(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8),
                        new HttpHeaders(), HttpStatus.OK));
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    void theWindowOperatorIsMandatoryBecauseWithoutItThirtyDaysIsEmpty() {
        // **这条不是凑覆盖率。** 探针实测：不带 when:30d 回 47 条只有 5 条在 30 天内
        // （最旧 5066 天前），带上回 7 条、7 条全在窗内。差别是决定性的——
        // 不带的话本地时间窗会把几乎所有条目滤掉，政策栏看起来像「近期没有政策」，
        // 而那是我们自己的查询写错了，不是事实。
        assertThat(PolicyNewsClient.query("电源设备")).isEqualTo("电源设备 政策 when:30d");
        assertThat(PolicyNewsClient.searchUrl("电源设备")).contains("when%3A30d");
    }

    @Test
    void theUrlCarriesTheChineseLocaleParameters() {
        // hl/gl/ceid 决定了返回的是简体中文、面向中国的条目。少一个就会混进别的语种，
        // 而本地的时间窗与相关性判据都不检查语种——混进来只会表现为「有些标题读不懂」。
        assertThat(PolicyNewsClient.searchUrl("电源设备")).isEqualTo(
                "https://news.google.com/rss/search?q="
                        + "%E7%94%B5%E6%BA%90%E8%AE%BE%E5%A4%87%20%E6%94%BF%E7%AD%96%20when%3A30d"
                        + "&hl=zh-CN&gl=CN&ceid=CN:zh-Hans");
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @Test
    void thePubDateIsConvertedFromGmtToShanghaiBecauseADayBoundaryIsAtStake() {
        // 第二条是 20:00 GMT —— 上海时间已经是**第二天** 04:00。
        // 不换算的话它会显示成 10-15，而事实是 10-16。
        // 章程 §8 要求每个时间都能回答「哪一日」，差一天就是答错了。
        NewsSourceResult r = PolicyNewsClient.parse(RSS);

        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).publishedOn()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(r.items().get(1).publishedOn()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void itemsCarryTheItemTitleAndSourceNotTheChannelOnes() {
        // channel 自己也有 <title>/<link>/<description>。用 getElementsByTagName 从文档根上捞
        // title 会先捞到频道的那个；childText 只取直接子节点，所以这里是对的——
        // 但这条得钉住，因为「政策栏每条的标题都是同一个频道名」是个很难一眼看出的错。
        NewsSourceResult r = PolicyNewsClient.parse(RSS);

        assertThat(r.items().get(0).title()).isEqualTo("国家能源局印发新型储能发展行动方案");
        assertThat(r.items().get(0).source()).isEqualTo("国家能源局");
        assertThat(r.items()).extracting(NewsItem::title).doesNotContain("电源设备 政策\" - Google 新闻");
    }

    @Test
    void theLinkIsGooglesRedirectAndWeSaySoRatherThanResolvingIt() {
        // Google News 的 link 是 news.google.com/rss/articles/… 的跳转地址，不是发布方原链。
        // 如实照用：它对复核仍然有效（点开经 Google 跳一次），
        // 自己反解真实 URL 是拿一个不稳定的规则换一个好看点的地址。
        NewsSourceResult r = PolicyNewsClient.parse(RSS);

        assertThat(r.items().get(0).url()).startsWith("https://news.google.com/rss/articles/");
    }

    @Test
    void theDescriptionHtmlIsNotCarried() {
        // §9.10：不读正文。description 里是 HTML 片段，连相关性判断都不用它。
        NewsSourceResult r = PolicyNewsClient.parse(RSS);

        assertThat(r.items()).extracting(NewsItem::title)
                .noneMatch(t -> t.contains("正文片段"))
                .noneMatch(t -> t.contains("href"));
    }

    @Test
    void anItemMissingPubDateOrLinkIsDroppedButStillCounted() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel>
                  <item><title>齐的</title><link>https://a/1</link>
                    <pubDate>Thu, 15 Oct 2026 01:00:00 GMT</pubDate></item>
                  <item><title>缺链接</title>
                    <pubDate>Thu, 15 Oct 2026 01:00:00 GMT</pubDate></item>
                  <item><title>缺日期</title><link>https://a/3</link></item>
                </channel></rss>
                """;
        NewsSourceResult r = PolicyNewsClient.parse(xml);

        assertThat(r.items()).hasSize(1);
        assertThat(r.rawCount()).isEqualTo(3);
    }

    @Test
    void anUnrecognisedPubDateYieldsNullRatherThanToday() {
        assertThat(PolicyNewsClient.publishedOn("Thu, 15 Oct 2026 01:06:54 GMT"))
                .isEqualTo(LocalDate.of(2026, 10, 15));
        // 换成 +0800 的数字偏移能不能认，取决于 RFC_1123 的宽松度——认不出就返回 null，
        // 上层丢弃并计数。**绝不能退回「今天」**：编出来的日期和真的长得一模一样。
        assertThat(PolicyNewsClient.publishedOn("2026-10-15")).isNull();
        assertThat(PolicyNewsClient.publishedOn("Thu, 32 Oct 2026 01:06:54 GMT")).isNull();
        assertThat(PolicyNewsClient.publishedOn(null)).isNull();
        assertThat(PolicyNewsClient.publishedOn("  ")).isNull();
    }

    // ------------------------------------------------------------------
    // XXE
    // ------------------------------------------------------------------

    @Test
    void aDocumentWithADoctypeIsRejectedRatherThanParsed() {
        // **本类最重要的一条。** RSS 是完全的外来输入。默认配置下，
        // 一个带 SYSTEM 声明的 DOCTYPE 会让解析器去读本地文件并把内容嵌进结果——
        // 也就是说一个 RSS 源就能读到服务器上的任意文件。
        //
        // 这里同时断言两件事：解析**失败**，且那个文件的内容**一条都没进到结果里**。
        // 只断言「没读到内容」是不够的（空条目的结果也满足），只断言「失败」也是不够的
        // （实现可能失败得晚，先展开再报错）。
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE rss [ <!ENTITY xxe SYSTEM "file:///c:/windows/win.ini"> ]>
                <rss version="2.0"><channel>
                  <item><title>&xxe;</title><link>https://a/1</link>
                    <pubDate>Thu, 15 Oct 2026 01:00:00 GMT</pubDate></item>
                </channel></rss>
                """;
        NewsSourceResult r = PolicyNewsClient.parse(xml);

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("不是可解析");
        assertThat(r.items()).isEmpty();
        assertThat(r.rawCount()).isZero();
    }

    @Test
    void aPlainParameterEntityCannotSmuggleAFileInEither() {
        // 上面那条走的是外部实体，这条走参数实体——两条不同的读文件路径，都关了。
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE rss [
                  <!ENTITY % file SYSTEM "file:///etc/passwd">
                  <!ENTITY % pe "<!ENTITY leak '%file;'>">
                  %pe;
                ]>
                <rss version="2.0"><channel>
                  <item><title>&leak;</title><link>https://a/1</link>
                    <pubDate>Thu, 15 Oct 2026 01:00:00 GMT</pubDate></item>
                </channel></rss>
                """;
        NewsSourceResult r = PolicyNewsClient.parse(xml);

        assertThat(r.ok()).isFalse();
        assertThat(r.items()).extracting(NewsItem::title).noneMatch(t -> t.contains("root:"));
    }

    // ------------------------------------------------------------------
    // 空 ≠ 坏
    // ------------------------------------------------------------------

    @Test
    void anHtmlInterceptionPageIsAFailureNotAnEmptyPolicyList() {
        // 被拦时回的也是 200，但体是 HTML。报成「近期没有政策」等于把
        // 「我们没取到」说成了「没有」——章程 §6 明令禁止。
        //
        // **这一段 HTML 是标签全闭合的**，也就是说它是一份合法 XML。
        // 光靠「解析抛没抛异常」判不出来，必须看根元素——这正是下面这个断言在钉的东西。
        NewsSourceResult r = PolicyNewsClient.parse("<html><body>403 Forbidden</body></html>");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("不是 RSS");
        assertThat(r.failureReason()).contains("html");
    }

    @Test
    void realWorldHtmlAlsoFailsAndTheReasonSaysWhichWay() {
        // 真实拦截页多半连 XML 都不是（<meta>/<br>/<link> 都不闭合），走的是解析异常那条路。
        // 两种形态的措辞都带上原因：一个是「不是 RSS」，一个是「不是可解析」——
        // 排查时「被拦了」和「源改版了」要能分开。
        NewsSourceResult r = PolicyNewsClient.parse(
                "<html><head><meta charset=\"utf-8\"><link rel=\"x\"></head>"
                        + "<body><br>Access Denied</body></html>");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("不是可解析");
    }

    @Test
    void aFeedWithNoItemsIsAnHonestEmptyResultNotAFailure() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0"><channel><title>无结果</title></channel></rss>
                """;
        NewsSourceResult r = PolicyNewsClient.parse(xml);

        assertThat(r.ok()).isTrue();
        assertThat(r.items()).isEmpty();
        assertThat(r.rawCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 传输
    // ------------------------------------------------------------------

    @Test
    void beingThrottledIsThrownNotSwallowed() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("连接被掐", new EOFException()));

        assertThatThrownBy(() -> client.searchPolicy("电源设备"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
    }

    @Test
    void otherTransportFailuresComeBackAsAResultSoTheOtherSegmentsKeepWorking() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("域名解析不了",
                        new java.net.UnknownHostException("news.google.com")));

        NewsSourceResult r = client.searchPolicy("电源设备");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("政策源不可达");
    }

    @Test
    void aBlankSubjectIsRejectedBeforeAnyCall() {
        assertThat(client.searchPolicy(null).ok()).isFalse();
        assertThat(client.searchPolicy("  ").ok()).isFalse();
    }

    @Test
    void anEndToEndSearchAgainstAMockedTransportReturnsParsedItems() {
        respondsWith(RSS);

        NewsSourceResult r = client.searchPolicy("电源设备");

        assertThat(r.ok()).isTrue();
        assertThat(r.category()).isEqualTo(NewsItem.Category.POLICY);
        assertThat(r.origin()).isEqualTo(PolicyNewsClient.ORIGIN);
        assertThat(r.items()).hasSize(2);
    }
}
