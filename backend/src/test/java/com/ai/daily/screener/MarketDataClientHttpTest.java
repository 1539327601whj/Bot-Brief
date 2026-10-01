package com.ai.daily.screener;

import com.ai.daily.config.MarketHttpClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 走**真实的 RestTemplate 转换器链**的外呼测试。
 *
 * <p>为什么非要有这一层：{@code MarketDataClient} 的其余测试全都把 RestTemplate mock 掉了，
 * 于是「请求发出去了、返回也拿到了，但 Spring 读不出这个响应」这一整类故障
 * 在那些测试里**根本不可能出现**。线上就是栽在这里：
 *
 * <pre>
 * Could not extract response: no suitable HttpMessageConverter found
 * for response type [interface java.util.Map] and content type [text/plain;charset=UTF-8]
 * </pre>
 *
 * <p>原因是 datacenter-web 回的是完全合法的 JSON，却把 Content-Type 声明成
 * {@code text/plain}，而 Jackson 转换器只认 {@code application/json}。
 * 这一类问题只能在「有真的转换器链」的测试里复现，所以这里用
 * {@link MockRestServiceServer}：它只替换掉底层连接，转换器一律用生产那套。
 */
class MarketDataClientHttpTest {

    /** 线上实测到的响应头：合法 JSON，声明成 text/plain，且**带** charset。 */
    private static final MediaType PLAIN_UTF8 =
            MediaType.parseMediaType("text/plain;charset=UTF-8");

    /** 只声明 text/plain、没有 charset。这时 Spring 会默认按 ISO-8859-1 读，中文必乱。 */
    private static final MediaType PLAIN_NO_CHARSET = MediaType.parseMediaType("text/plain");

    private static final String TRADE_DATE_BODY =
            "{\"version\":\"8168ad97\",\"success\":true,"
                    + "\"result\":{\"pages\":1,\"data\":[{\"TRADE_DATE\":\"2026-09-30 00:00:00\"}]}}";

    private static final String LIST_BODY =
            "{\"version\":\"8168ad97\",\"success\":true,\"result\":{\"pages\":12,\"count\":5572,"
                    + "\"data\":["
                    + "{\"SECURITY_CODE\":\"601398\",\"SECUCODE\":\"601398.SH\","
                    + "\"SECURITY_NAME_ABBR\":\"工商银行\",\"TOTAL_MARKET_CAP\":2.4e12},"
                    + "{\"SECURITY_CODE\":\"000001\",\"SECUCODE\":\"000001.SZ\","
                    + "\"SECURITY_NAME_ABBR\":\"平安银行\",\"TOTAL_MARKET_CAP\":2.5e11},"
                    + "{\"SECURITY_CODE\":\"920014\",\"SECUCODE\":\"920014.BJ\","
                    + "\"SECURITY_NAME_ABBR\":\"特瑞斯\",\"TOTAL_MARKET_CAP\":1.0e9}]}}";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        // 用生产那个配置类来造，保证转换器链与线上完全一致。
        // MockRestServiceServer 只换掉底层连接，不动转换器。
        restTemplate = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private MarketDataClient client() {
        return new MarketDataClient(restTemplate, 250, 500, 50);
    }

    // ------------------------------------------------------------------
    // 线上那个 bug
    // ------------------------------------------------------------------

    @Test
    void jsonDeclaredAsPlainTextIsStillParsed() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess(TRADE_DATE_BODY, PLAIN_UTF8));

        LocalDate date = client().latestTradeDate();

        assertThat(date).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void thePoolListIsParsedEvenThoughTheServerCallsItPlainText() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess(LIST_BODY, PLAIN_UTF8));

        List<StockRow> rows = client().fetchListByMarketCap(LocalDate.of(2026, 9, 30));

        // 北交所在清单这一步就丢掉，所以是 2 只不是 3 只
        assertThat(rows).extracting(StockRow::getCode).containsExactly("601398", "000001");
        assertThat(rows.get(0).getTotalMarketCap()).isEqualByComparingTo("2400000000000");
        // 中文必须完好。乱码能通过 JSON 解析，所以只断言「解析成功」是拦不住的。
        assertThat(rows).extracting(StockRow::getName).containsExactly("工商银行", "平安银行");
    }

    // ------------------------------------------------------------------
    // 字符集：这里错了不会报错，只会静默出乱码
    // ------------------------------------------------------------------

    @Test
    void chineseSurvivesWhenTheServerForgetsToDeclareACharset() {
        // 只声明 text/plain、不带 charset 时，Spring 的 StringHttpMessageConverter
        // 会退回 ISO-8859-1，中文变成 æ¥åä¸åæ¨。
        // 这类损坏**不报错**：JSON 结构全是 ASCII，照样解析成功，只有名字是坏的。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess(LIST_BODY, PLAIN_NO_CHARSET));

        List<StockRow> rows = client().fetchListByMarketCap(LocalDate.of(2026, 9, 30));

        assertThat(rows).extracting(StockRow::getName).containsExactly("工商银行", "平安银行");
    }

    @Test
    void aDeclaredNonUtf8CharsetIsHonouredRatherThanOverridden() {
        // 服务端**明确**声明了就不是「没声明」，此时必须听它的，不能一律按 UTF-8 硬解。
        byte[] gbk = LIST_BODY.getBytes(java.nio.charset.Charset.forName("GBK"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess(gbk, MediaType.parseMediaType("text/plain;charset=GBK")));

        List<StockRow> rows = client().fetchListByMarketCap(LocalDate.of(2026, 9, 30));

        assertThat(rows).extracting(StockRow::getName).containsExactly("工商银行", "平安银行");
    }

    @Test
    void theCharsetFallbackIsUtf8BecauseThatIsWhatJsonRequires() {
        assertThat(MarketDataClient.charsetOf(null)).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(MarketDataClient.charsetOf(PLAIN_NO_CHARSET))
                .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(MarketDataClient.charsetOf(PLAIN_UTF8))
                .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 这条是上面那些测试的「为什么」：同一份响应，直接要 {@code Map.class} 就是线上那句报错。
     *
     * <p>它证明这个响应形态**确实**需要绕开 Jackson 转换器，而不是我们多此一举。
     * 谁要是哪天把 getJson 改回 {@code Map.class}，这条会先红，而且红得能看懂。
     */
    @Test
    void askingForAMapDirectlyCannotReadThisResponseAtAll() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess(TRADE_DATE_BODY, PLAIN_UTF8));

        assertThatThrownBy(() -> restTemplate.exchange(
                URI.create("https://datacenter-web.eastmoney.com/api/data/v1/get"),
                HttpMethod.GET, new HttpEntity<>(Map.of()), Map.class))
                .isInstanceOf(org.springframework.web.client.RestClientException.class)
                .hasMessageContaining("no suitable HttpMessageConverter");
    }

    // ------------------------------------------------------------------
    // 解析失败要能说出「服务端到底回了什么」
    // ------------------------------------------------------------------

    @Test
    void aPlainTextErrorBodyIsQuotedInTheExceptionInsteadOfBeingSwallowed() {
        // filter 写错时服务端就是这个形态：纯文本，不是 JSON。
        // 直接要 Map.class 的话，这条最关键的信息会被转换器吞掉。
        String antlrError = "参数预处理错误:org.antlr.v4.runtime.NoViableAltException";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess(antlrError, PLAIN_UTF8));

        assertThatThrownBy(() -> client().latestTradeDate())
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("不是 JSON")
                .hasMessageContaining("NoViableAltException")
                .hasMessageContaining("datacenter-web.eastmoney.com/api/data/v1/get");
    }

    @Test
    void anEmptyBodyIsReportedAsAnEmptyResponseNotAsAParseFailure() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess("   ", PLAIN_UTF8));

        assertThatThrownBy(() -> client().latestTradeDate())
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("空响应");
    }

    @Test
    void aJsonBodyThatParsesButHasNoResultSaysSoInPlainWords() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andRespond(withSuccess("{\"success\":false,\"message\":\"报告不存在\"}", PLAIN_UTF8));

        assertThatThrownBy(() -> client().latestTradeDate())
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("报告不存在");
    }

    // ------------------------------------------------------------------
    // 请求头与 URL 也要在这条真实链路上验一遍
    // ------------------------------------------------------------------

    @Test
    void theRawFilterSurvivesTheRealRequestPipeline() {
        // listUrl 手拼的 filter 含括号、引号、等号。这里的 RestTemplate 是生产那一个，
        // 「会不会被二次编码」只有在真实链路上才测得准。
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("&filter=(TRADE_DATE='2026-09-30')"),
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("%28")))))
                .andRespond(withSuccess(LIST_BODY, PLAIN_UTF8));

        List<StockRow> rows = client().fetchListByMarketCap(LocalDate.of(2026, 9, 30));

        assertThat(rows).isNotEmpty();
        server.verify();
    }

    @Test
    void theRefererIsSentBecauseBothHostsRejectRequestsWithoutIt() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/data/v1/get")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header("Referer", "https://data.eastmoney.com/"))
                .andRespond(withSuccess(TRADE_DATE_BODY, PLAIN_UTF8));

        client().latestTradeDate();

        server.verify();
    }
}