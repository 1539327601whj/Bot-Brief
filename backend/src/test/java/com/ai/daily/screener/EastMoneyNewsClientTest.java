package com.ai.daily.screener;

import com.fasterxml.jackson.databind.JsonNode;
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
 * {@link EastMoneyNewsClient} 的解析。夹具照 {@code verify_analysis_sources.py}
 * 第二节**实际拿回来的响应形态**写：{@code result.cmsArticleWebOld[]}，
 * 字段 {@code date / title / url / mediaName}，标题带 {@code <em>} 高亮。
 */
class EastMoneyNewsClientTest {

    private static final String CALLBACK = EastMoneyNewsClient.CALLBACK;

    /** 探针原文的形态。第二行刻意用「不含标的名」的标题——那是管线的活儿，不是客户端的。 */
    private static final String PAYLOAD = """
            {"code":0,"msg":"","hitsTotal":2,"result":{"cmsArticleWebOld":[
              {"date":"2026-10-15 11:07:27",
               "mediaName":"证券时报",
               "title":"<em>阳光电源</em>等在内蒙古成立智算科技公司",
               "url":"https://finance.eastmoney.com/a/202610153171234567.html",
               "content":"阳光电源（300274）10月15日公告，拟出资5000万元……"},
              {"date":"2026-10-13 08:30:00",
               "mediaName":"每日经济新闻",
               "title":"光伏逆变器出口回暖 龙头订单排至明年",
               "url":"https://finance.eastmoney.com/a/202610133171234500.html",
               "content":"正文片段，这一段不该出现在结果里的任何地方"}
            ]}}
            """;

    private RestTemplate restTemplate;
    private EastMoneyNewsClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new EastMoneyNewsClient(restTemplate);
    }

    private void respondsWith(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8),
                        new HttpHeaders(), HttpStatus.OK));
    }

    private static JsonNode shell(String json) {
        return NewsWire.jsonp(CALLBACK + "(" + json + ")", CALLBACK);
    }

    // ------------------------------------------------------------------
    // 请求参数
    // ------------------------------------------------------------------

    @Test
    void theKeywordIsSentAsRawUtf8NotAsUnicodeEscapes() {
        // Jackson 默认会把非 ASCII 转义成 阳光…，而**探针验证过能用的那一种
        // 是原样 UTF-8**。转义后的形态没人验证过，所以这里钉住「不许走 Jackson」。
        String param = EastMoneyNewsClient.searchParam("阳光电源");

        assertThat(param).contains("\"keyword\":\"阳光电源\"");
        assertThat(param).doesNotContain("\\u");
        assertThat(EastMoneyNewsClient.searchUrl("阳光电源"))
                .contains("%22keyword%22%3A%22%E9%98%B3%E5%85%89%E7%94%B5%E6%BA%90%22")
                .doesNotContain("%5Cu9633");
    }

    @Test
    void quotesAndBackslashesAreEscapedSoAKeywordCannotBreakOutOfTheJson() {
        // 关键词来自标的名称与行业名，正常不会有引号——但拼进 JSON 之前挡一道的成本是零，
        // 不挡的话一个带引号的行业名就会让服务端收到一个非法 JSON（回 400），
        // 而表现是「资讯栏空着」。
        assertThat(EastMoneyNewsClient.jsonEscape("a\"b")).isEqualTo("a\\\"b");
        assertThat(EastMoneyNewsClient.jsonEscape("a\\b")).isEqualTo("a\\\\b");
        assertThat(EastMoneyNewsClient.jsonEscape("a\nb")).isEqualTo("a\\nb");
        // 中文、全角标点都原样留着
        assertThat(EastMoneyNewsClient.jsonEscape("电源设备（A股）")).isEqualTo("电源设备（A股）");
    }

    @Test
    void thePageSizeIsPinnedToOnePageBecauseWeNeverPage() {
        // 章程 §9.8：每源只发一次，不翻页。多取是为了给去重留余量，不是允许翻页。
        assertThat(EastMoneyNewsClient.searchParam("x")).contains("\"pageIndex\":1,"
                + "\"pageSize\":20,");
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @Test
    void theEmphasisTagsSurviveBecauseCleansingHappensInExactlyOnePlace() {
        // **这条是刻意的。** 客户端只取字段，剥标签集中在 NewsDigestService。
        // 如果这里也剥一道，就会出现「这一路剥了那一路没剥」——而两路的结果要么互相
        // 去重不掉（一个带标签一个不带），要么看起来像同一条。清洗只能有一处。
        NewsSourceResult r = EastMoneyNewsClient.parse(shell(PAYLOAD));

        assertThat(r.items().get(0).title())
                .isEqualTo("<em>阳光电源</em>等在内蒙古成立智算科技公司");
        assertThat(r.items().get(0).title()).contains("<em>");
    }

    @Test
    void theBodySnippetIsDeliberatelyNotCarriedAnywhere() {
        // 章程 §9.10：不读正文。`content` 是正文片段，连相关性判断都不用它是为了防止
        // 「正文里提到一次」被当成相关性命中。
        //
        // 这里断言的是**结构**而不是某个字符串：把字段集合钉死，将来谁往 NewsItem 上加一个
        // body/description 字段，这条就会红，并把他引回 §9.10。
        NewsSourceResult r = EastMoneyNewsClient.parse(shell(PAYLOAD));

        assertThat(NewsItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("category", "origin", "originLabel", "title",
                        "source", "publishedOn", "url", "noticeCategory");
        assertThat(r.items().get(0).noticeCategory()).isNull();
    }

    @Test
    void theMediaNameIsKeptAsTheSourceAndTheDayComesFromDate() {
        NewsSourceResult r = EastMoneyNewsClient.parse(shell(PAYLOAD));

        assertThat(r.items()).hasSize(2);
        assertThat(r.items().get(0).source()).isEqualTo("证券时报");
        assertThat(r.items().get(0).publishedOn()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(r.items().get(1).publishedOn()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(r.category()).isEqualTo(NewsItem.Category.NEWS);
    }

    @Test
    void aRowMissingUrlOrDateIsDroppedButStillCountedInRawCount() {
        String json = """
                {"code":0,"msg":"","hitsTotal":4,"result":{"cmsArticleWebOld":[
                  {"date":"2026-10-15 11:07:27","title":"齐的","url":"https://a/1","mediaName":"甲"},
                  {"date":"2026-10-15 11:07:27","title":"缺链接"},
                  {"title":"缺日期","url":"https://a/3","mediaName":"丙"},
                  {"date":"2026-10-15 11:07:27","url":"https://a/4","mediaName":"丁"}
                ]}}
                """;
        NewsSourceResult r = EastMoneyNewsClient.parse(shell(json));

        assertThat(r.items()).hasSize(1);
        assertThat(r.rawCount()).isEqualTo(4);
    }

    @Test
    void noMediaNameIsNullNotAnInventedSource() {
        String json = """
                {"code":0,"msg":"","hitsTotal":1,"result":{"cmsArticleWebOld":[
                  {"date":"2026-10-15 11:07:27","title":"没写媒体","url":"https://a/1","mediaName":""}
                ]}}
                """;
        NewsSourceResult r = EastMoneyNewsClient.parse(shell(json));

        // 东财用 "" 表示没有。按缺失处理，**不编一个「东财资讯」顶上**——
        // 那会让「这条没有出处」看起来像「这条出自东财资讯」。
        assertThat(r.items().get(0).source()).isNull();
    }

    // ------------------------------------------------------------------
    // 空 ≠ 坏
    // ------------------------------------------------------------------

    @Test
    void aBusinessErrorIsReportedWithTheServersOwnMessage() {
        // 探针实测：param 编两次时服务端回 {"code":400,"msg":"非法的json格式"}。
        // 这句 msg 是排查时唯一有用的东西，必须带上来。
        NewsSourceResult r = EastMoneyNewsClient.parse(shell("{\"code\":400,\"msg\":\"非法的json格式\"}"));

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("非法的json格式");
    }

    @Test
    void anEmptyResultObjectIsStructuralFailureNotZeroHits() {
        // result 是空对象 = 结构变了；result.cmsArticleWebOld 是空数组 = 真的搜到 0 条。
        // 后者是正常结果，前者要有人去看。把两者都说成「没有新闻」是最坏的一种。
        NewsSourceResult structural = EastMoneyNewsClient.parse(shell("{\"code\":0,\"msg\":\"\",\"result\":{}}"));
        assertThat(structural.ok()).isFalse();
        assertThat(structural.failureReason()).contains("结构异常");
        assertThat(structural.failureReason()).contains("cmsArticleWebOld");

        NewsSourceResult genuinelyEmpty = EastMoneyNewsClient.parse(
                shell("{\"code\":0,\"msg\":\"\",\"hitsTotal\":0,\"result\":{\"cmsArticleWebOld\":[]}}"));
        assertThat(genuinelyEmpty.ok()).isTrue();
        assertThat(genuinelyEmpty.items()).isEmpty();
        assertThat(genuinelyEmpty.empty()).isTrue();
    }

    // ------------------------------------------------------------------
    // 传输
    // ------------------------------------------------------------------

    @Test
    void anHtmlInterceptionPageComesBackAsAFailureCarryingWhatTheServerSaid() {
        // 服务端被拦或改版时回 HTML，状态码仍是 200。走的是 jsonp 那条路，
        // 必须报出原文，不能静默变成「没有新闻」。
        respondsWith("<html><body>403 Forbidden</body></html>");

        NewsSourceResult r = client.search("阳光电源");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("资讯源不可达");
    }

    @Test
    void beingThrottledIsThrownNotSwallowed() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("连接被掐", new EOFException()));

        assertThatThrownBy(() -> client.search("阳光电源"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
    }

    @Test
    void aBlankKeywordIsRejectedBeforeAnyCall() {
        assertThat(client.search(null).ok()).isFalse();
        assertThat(client.search("").ok()).isFalse();
        assertThat(client.search("   ").ok()).isFalse();
    }

    @Test
    void theKeywordIsTrimmedBeforeItGoesIntoTheUrl() {
        // 带空格的查询词编出来的 URL 与不带的不同，服务端认的是不带的那条。
        // 探针验过的形态是「阳光电源」，不该因为调用方传了「 阳光电源 」就打第二条。
        String url = EastMoneyNewsClient.searchUrl("阳光电源");
        assertThat(EastMoneyNewsClient.searchUrl("  阳光电源  ".strip())).isEqualTo(url);
    }

    @Test
    void anEndToEndSearchAgainstAMockedTransportReturnsParsedItems() {
        respondsWith(CALLBACK + "(" + PAYLOAD + ")");

        NewsSourceResult r = client.search("阳光电源");

        assertThat(r.ok()).isTrue();
        assertThat(r.origin()).isEqualTo(EastMoneyNewsClient.ORIGIN);
        assertThat(r.items()).hasSize(2);
    }
}
