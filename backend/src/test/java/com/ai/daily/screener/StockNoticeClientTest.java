package com.ai.daily.screener;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link StockNoticeClient} 的解析。夹具用的是 {@code verify_analysis_sources.py}
 * **实际拿回来的那段响应原文**（2026-10-15 探针第一节）——
 * 拿真响应当夹具，才可能发现「字段名和我想的不一样」这类错。
 */
class StockNoticeClientTest {

    /** 探针原文的片段。字段名、日期格式、分类字段都照抄，一个字没改。 */
    private static final String NOTICE_JSON = """
            {"data":{"list":[
              {"art_code":"AN202610151830508442",
               "codes":[{"ann_type":"A,CYB","market_code":"0","short_name":"阳光电源","stock_code":"300274"}],
               "columns":[{"column_code":"001002006010","column_name":"股份质押、冻结"}],
               "display_time":"2026-10-15 16:02:03:301",
               "notice_date":"2026-10-15 00:00:00",
               "title":"阳光电源:关于控股股东部分股份解除质押的公告"},
              {"art_code":"AN202610142211334455",
               "codes":[{"market_code":"0","stock_code":"300274"}],
               "columns":[],
               "display_time":"2026-10-15 09:30:00:000",
               "notice_date":"2026-10-14 00:00:00",
               "title":"阳光电源:关于董事会决议的公告"}
            ],"page_index":1,"page_size":20,"total_hits":20},"error":"","success":1}
            """;

    private RestTemplate restTemplate;
    private StockNoticeClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new StockNoticeClient(restTemplate);
    }

    private void respondsWith(String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenReturn(new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8),
                        new HttpHeaders(), HttpStatus.OK));
    }

    private static Map<String, Object> asMap(String json) throws Exception {
        return NewsWire.MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    // ------------------------------------------------------------------
    // URL
    // ------------------------------------------------------------------

    @Test
    void theUrlIsTheOneTheProbeGotA200From() {
        assertThat(StockNoticeClient.url("300274")).isEqualTo(
                "https://np-anotice-stock.eastmoney.com/api/security/ann"
                        + "?sr=-1&page_size=20&page_index=1&ann_type=A&client_source=web&stock_list=300274");
    }

    @Test
    void theDetailUrlIsBuiltFromTheArtCodeBecauseTheResponseHasNoUrlField() {
        // 响应里没有现成的 URL，art_code 是唯一能定位一条公告的东西。
        // 这个形态实测**可达**，但页面是 JS 壳（真 art_code 与假 art_code 回的是同一个 200），
        // 所以「打得开」验证过、「打开的是不是这一条」没有——见类注释。
        assertThat(StockNoticeClient.detailUrl("300274", "AN202610151830508442")).isEqualTo(
                "https://data.eastmoney.com/notices/detail/300274/AN202610151830508442.html");
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    @Test
    void theDayComesFromNoticeDateNotDisplayTime() throws Exception {
        // **这条是探针那 9/10 条差异撑起来的。** 两个字段含义不同（公告日 vs 入库时间），
        // 上面第二条夹具的 display_time 是 10-15 而 notice_date 是 10-14——
        // 取错了不会有任何报错，只是页面上有一批日期悄悄偏一天。
        NewsSourceResult r = StockNoticeClient.parse("300274", asMap(NOTICE_JSON));

        assertThat(r.ok()).isTrue();
        assertThat(r.items()).hasSize(2);
        assertThat(r.items()).extracting(NewsItem::publishedOn)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 14));
    }

    @Test
    void theNoticeCategoryIsCarriedThroughSoHoldingItemsHaveAProvenance() throws Exception {
        // columns[].column_name 是公告分类。章程 §9.12 原来写着「筹码与预期没有数据源」——
        // 探针纠正了这一点：它有出处，实测出现的就是「股份质押、冻结」。
        NewsSourceResult r = StockNoticeClient.parse("300274", asMap(NOTICE_JSON));

        assertThat(r.items().get(0).noticeCategory()).isEqualTo("股份质押、冻结");
        assertThat(StockNoticeClient.isHoldingRelated(r.items().get(0).noticeCategory())).isTrue();
        // 第二条没有分类。**不编一个「其他」**——空着比写个假的强。
        assertThat(r.items().get(1).noticeCategory()).isNull();
        assertThat(StockNoticeClient.isHoldingRelated(null)).isFalse();
    }

    @Test
    void theDetailLinkPrefersTheCodeTheAnnouncementItselfCarries() throws Exception {
        // 用**另一个代码**去解析：如果实现图省事直接拿查询代码拼链接，这条就会挂。
        // 同一份公告挂多个代码时（A+H 之类），自带的那个才是它真正的归属。
        NewsSourceResult r = StockNoticeClient.parse("600000", asMap(NOTICE_JSON));

        assertThat(r.items().get(0).url())
                .isEqualTo("https://data.eastmoney.com/notices/detail/300274/AN202610151830508442.html");
    }

    @Test
    void aRowWithNoCodesFallsBackToTheQueriedCode() throws Exception {
        // 自带的代码缺失时退回查询代码——**但要能看出来这是退路**，
        // 所以这里同时断言它没有变成空串（空串会拼出 /detail//x.html 这种半截链接）。
        String json = """
                {"data":{"list":[
                  {"art_code":"AN9","notice_date":"2026-10-15 00:00:00","title":"没有 codes 段"}
                ]},"success":1}
                """;
        NewsSourceResult r = StockNoticeClient.parse("300274", asMap(json));

        assertThat(r.items().get(0).url())
                .isEqualTo("https://data.eastmoney.com/notices/detail/300274/AN9.html");
    }

    @Test
    void aRowWithoutAnArtCodeOrDateIsDroppedButStillCountedInRawCount() throws Exception {
        // 丢掉的那些**要能被看见**：rawCount 是源回了几条，items 是能上页面的。
        // 两个数分开，「本次只取到 N 条」才带得上原因——
        // 是源本来就回得少，还是回了 20 条有 17 条解析不了？处置完全不同。
        String json = """
                {"data":{"list":[
                  {"art_code":"AN1","notice_date":"2026-10-15 00:00:00","title":"有 art_code 的"},
                  {"notice_date":"2026-10-15 00:00:00","title":"缺 art_code 的"},
                  {"art_code":"AN3","title":"缺日期的"},
                  {"art_code":"AN4","notice_date":"2026-10-15 00:00:00"}
                ],"total_hits":4},"success":1}
                """;
        NewsSourceResult r = StockNoticeClient.parse("300274", asMap(json));

        assertThat(r.items()).hasSize(1);
        assertThat(r.rawCount()).isEqualTo(4);
        assertThat(r.allDropped()).isFalse();
    }

    // ------------------------------------------------------------------
    // 空 ≠ 坏
    // ------------------------------------------------------------------

    @Test
    void anEtfWithNoAnnouncementsIsAnEmptyResultNotAFailure() throws Exception {
        // **这是本类最重要的一条。** 探针实测：stock_list=510300 回
        // {"data":{"list":[],"total_hits":0},"success":1}——字段名是对的、接口认这个代码，
        // 是**真空**。基金公告不从这条路出。
        //
        // 报成失败的话，页面上会说「公告源不可达」，而真相是「这只 ETF 在这里就是没有公告」——
        // 把「我们没有这个数据源」说成「源挂了」，排查会一路往网络上去找。
        String json = """
                {"data":{"list":[],"page_index":1,"page_size":20,"total_hits":0},"error":"","success":1}
                """;
        NewsSourceResult r = StockNoticeClient.parse("510300", asMap(json));

        assertThat(r.ok()).isTrue();
        assertThat(r.items()).isEmpty();
        assertThat(r.rawCount()).isZero();
        assertThat(r.empty()).isTrue();
    }

    @Test
    void aMissingListFieldIsAFailureAndSaysSo() throws Exception {
        // 「结构变了」与「没有公告」必须分开：前者要有人去看，后者是正常结果。
        NewsSourceResult r = StockNoticeClient.parse("300274", asMap("{\"data\":{},\"success\":1}"));

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("没有 list");
    }

    @Test
    void aResponseWithoutTheDataSectionIsAFailure() throws Exception {
        NewsSourceResult r = StockNoticeClient.parse("300274", asMap("{\"error\":\"boom\",\"success\":0}"));
        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("没有 data 段");
    }

    // ------------------------------------------------------------------
    // 限流
    // ------------------------------------------------------------------

    @Test
    void beingThrottledIsThrownNotSwallowed() {
        // 章程 §9.7：五段失败域各自独立，**唯独被限流必须往外抛**——
        // 那说明这个源在按 IP 计时封我们，吞掉就等于让上层继续打。
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("连接被掐", new EOFException()));

        assertThatThrownBy(() -> client.fetchNotices("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
    }

    @Test
    void otherFailuresComeBackAsAResultSoTheOtherSegmentsKeepWorking() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("域名解析不了",
                        new java.net.UnknownHostException("np-anotice-stock.eastmoney.com")));

        NewsSourceResult r = client.fetchNotices("300274");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("公告源不可达");
        assertThat(r.items()).isEmpty();
    }

    @Test
    void aCodeThatCannotBeAStockCodeIsRejectedBeforeAnyCall() {
        // 代码会被拼进 URL。拼之前必须确认它真的是代码，否则等于把查询串交给调用方拼。
        // 末尾那句 verifyNoInteractions 是这条测试的重点：只断言「结果是失败」的话，
        // 一个先发请求再失败、或者干脆抛 NPE 被 catch 住的实现也能通过。
        assertThat(client.fetchNotices("300274;drop").ok()).isFalse();
        assertThat(client.fetchNotices(null).ok()).isFalse();
        assertThat(client.fetchNotices("").ok()).isFalse();
        assertThat(client.fetchNotices("证券代码").ok()).isFalse();

        verifyNoInteractions(restTemplate);
    }

    @Test
    void anEndToEndFetchAgainstAMockedTransportReturnsParsedItems() {
        respondsWith(NOTICE_JSON);

        NewsSourceResult r = client.fetchNotices("300274");

        assertThat(r.ok()).isTrue();
        assertThat(r.category()).isEqualTo(NewsItem.Category.NOTICE);
        assertThat(r.origin()).isEqualTo(StockNoticeClient.ORIGIN);
        assertThat(r.items()).hasSize(2);
    }
}
