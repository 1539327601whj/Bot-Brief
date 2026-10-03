package com.ai.daily.screener;

import com.ai.daily.config.MarketHttpClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestTemplate;

import java.net.SocketException;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link StockValuationClient} 的测试，走**真实的转换器链**（{@link MockRestServiceServer}），
 * 与 {@code MarketDataClientHttpTest} 同一套理由：datacenter-web 把合法 JSON 声明成
 * {@code text/plain}，只有在真转换器链下才复现得出来。
 *
 * <p>最要紧的两条是「请求长什么样」，不是「响应怎么解析」：
 * <ol>
 *   <li><b>{@code filter} 里的代码不能加引号</b>。加了引号服务端会回 400 或静默 0 行，
 *       而这个源是**个股 PE 分位的唯一来源**——写错了整页就只剩价格。</li>
 *   <li><b>只打 {@code datacenter-web}</b>。{@link MockRestServiceServer} 只登记了这一个域名，
 *       真去打了别的域名会直接失败，比断言 URL 前缀更难绕过。</li>
 * </ol>
 */
class StockValuationClientTest {

    /** 生产实测的响应头：合法 JSON，声明成 text/plain，带 charset。 */
    private static final MediaType PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /** 服务端按 sortTypes=-1 返回**降序**，这里刻意照抄那个顺序。 */
    private static final String HISTORY_BODY = """
            {"version":"8168ad97","success":true,"result":{"pages":1,"count":4,"data":[
              {"SECURITY_CODE":"300274","SECUCODE":"300274.SZ","SECURITY_NAME_ABBR":"阳光电源",
               "TRADE_DATE":"2026-09-30 00:00:00","PE_TTM":15.56813116,"PE_LAR":12.70452817,
               "PB_MRQ":3.41082893,"CLOSE_PRICE":82.49},
              {"SECURITY_CODE":"300274","SECUCODE":"300274.SZ","SECURITY_NAME_ABBR":"阳光电源",
               "TRADE_DATE":"2026-09-29 00:00:00","PE_TTM":15.10,"PE_LAR":12.3,
               "PB_MRQ":3.3,"CLOSE_PRICE":80.10},
              {"SECURITY_CODE":"300274","SECUCODE":"300274.SZ","SECURITY_NAME_ABBR":"阳光电源",
               "TRADE_DATE":"2018-01-03 00:00:00","PE_TTM":20.0,"PE_LAR":16.0,
               "PB_MRQ":4.0,"CLOSE_PRICE":11.80},
              {"SECURITY_CODE":"300274","SECUCODE":"300274.SZ","SECURITY_NAME_ABBR":"阳光电源",
               "TRADE_DATE":"2018-01-02 00:00:00","PE_TTM":19.5,"PE_LAR":15.5,
               "PB_MRQ":3.9,"CLOSE_PRICE":11.50}
            ]}}""";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private StockValuationClient client() {
        return new StockValuationClient(restTemplate);
    }

    // ------------------------------------------------------------------
    // 请求长什么样 —— 这个源是唯一的，请求写错就等于没有 PE 分位
    // ------------------------------------------------------------------

    @Test
    void theStockCodeIsFilteredWithoutQuotes() {
        // 这是本类最要紧的一条。实测：filter=(SECURITY_CODE="300274") 永远拿不到数据。
        // 同一个接口上 TRADE_DATE 反而**必须**带引号，所以「统一加引号」在这里是错的。
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("filter=(SECURITY_CODE=300274)"),
                        // 编码后的双引号 %22 一旦出现，这个测试就该红
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("%22")))))
                .andRespond(withSuccess(HISTORY_BODY, PLAIN_UTF8));

        assertThat(client().fetchHistory("300274").ok()).isTrue();
        server.verify();
    }

    @Test
    void theFilterIsNotPercentEncodedBecauseTheServerDoesNotDecodeIt() {
        // 服务端用 ANTLR 解析 filter 且**不做 URL 解码**：`=` 变成 %3D 就直接回
        // 「参数预处理错误: NoViableAltException」。第一版用
        // UriComponentsBuilder...build().encode() 正是踩了这个，而线上表现会是
        // 「查个股永远没有 PE 分位」——看起来像源本身没数据，不像请求写错了。
        //
        // 断言整条 filter 原文，而不是只断言「不含 %22」：后者挡不住 %3D、%28、%29 这些。
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "&filter=(SECURITY_CODE=300274)&")))
                .andRespond(withSuccess(HISTORY_BODY, PLAIN_UTF8));

        client().fetchHistory("300274");
        server.verify();
    }

    @Test
    void itAsksForTheValuationAnalysisReportAndCruciallyNotAllColumns() {
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("reportName=RPT_VALUEANALYSIS_DET"),
                        org.hamcrest.Matchers.containsString("PE_TTM"),
                        // columns=ALL 会把响应放大四倍，而这里一次要点十年
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("columns=ALL")),
                        org.hamcrest.Matchers.containsString("sortTypes=-1"))))
                .andRespond(withSuccess(HISTORY_BODY, PLAIN_UTF8));

        client().fetchHistory("300274");
        server.verify();
    }

    @Test
    void onlyTheDatacenterWebDomainIsTouched() {
        // MockRestServiceServer 只登记了 datacenter-web。真去打了 push2（或任何别的域名），
        // 请求会匹配不上而抛错——这比断言 URL 前缀更难绕过。
        server.expect(MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.startsWith("https://datacenter-web.eastmoney.com/")))
                .andRespond(withSuccess(HISTORY_BODY, PLAIN_UTF8));

        client().fetchHistory("300274");
        server.verify();
    }

    @Test
    void aCodeThatIsNotSixDigitsFailsWithoutAnyRequest() {
        // 代码会被拼进 filter。不校验就等于把查询串交给调用方拼。
        for (String bad : new String[]{"30027", "3002745", "30027a", "", null, "'; DROP--"}) {
            StockValuationClient.History h = client().fetchHistory(bad);
            assertThat(h.ok()).as("code=%s", bad).isFalse();
            assertThat(h.failureReason()).contains("6 位数字");
        }
        server.verify();   // 一条请求都没发
    }

    // ------------------------------------------------------------------
    // 响应怎么解析
    // ------------------------------------------------------------------

    @Test
    void theHistoryComesBackAscendingEvenThoughTheServerSendsItDescending() {
        // 不依赖服务端的 sortTypes：依赖它的话，对方改默认排序就会静默给出反向的基线，
        // 而「三年前分位 10」和「三年前分位 90」在页面上长得一样合理。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(HISTORY_BODY, PLAIN_UTF8));

        StockValuationClient.History h = client().fetchHistory("300274");

        assertThat(h.points()).extracting(StockValuationClient.Point::tradeDate)
                .containsExactly(LocalDate.of(2018, 1, 2), LocalDate.of(2018, 1, 3),
                        LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));
    }

    @Test
    void tradeDateIsTrimmedFromTimestampToDay() {
        // 服务端给的是 "2018-01-02 00:00:00"。取错位数会解析失败并静默跳过整行，
        // 于是历史凭空少好几年——而分位会照算，只是窗口变短。
        assertThat(StockValuationClient.tradeDate("2026-09-30 00:00:00"))
                .isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(StockValuationClient.tradeDate("2026-09-30")).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void anUnrecognisedTradeDateFormatIsSkippedRatherThanGuessed() {
        // 猜日期会让一行坏数据变成一行错数据。返回 null → 该行被丢掉，
        // 而 rawCount 与 points.size() 的差会让页面能说出「少了几行」。
        assertThat(StockValuationClient.tradeDate("30/09/2026")).isNull();
        assertThat(StockValuationClient.tradeDate("")).isNull();
        assertThat(StockValuationClient.tradeDate(null)).isNull();
        assertThat(StockValuationClient.tradeDate(20260930)).isNull();
    }

    @Test
    void peAndPbAndCloseAreReadFromTheirOwnColumns() {
        // PE_LAR 就在同一个响应里，取错了不会有任何报错，只是分位从此偏一点。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(HISTORY_BODY, PLAIN_UTF8));

        StockValuationClient.History h = client().fetchHistory("300274");
        StockValuationClient.Point last = h.points().get(h.points().size() - 1);

        assertThat(h.name()).isEqualTo("阳光电源");
        assertThat(last.peTtm()).isEqualByComparingTo("15.56813116");   // 不是 12.70452817
        assertThat(last.pbMrq()).isEqualByComparingTo("3.41082893");
        assertThat(last.closePrice()).isEqualByComparingTo("82.49");
    }

    @Test
    void aMissingPeBecomesNullAndIsCountedRatherThanSilentlyDroppedFromTheWindow() {
        // 东财用 "-" 表示缺失。缺失必须保持 null：变成 0 会让它算成「史上最便宜」，
        // 而那正是分位会严重失真的地方。这个数还必须能单独报给页面——它不进 PE 窗口，
        // 分位的分母就少一天，而整条曲线只会偏一点，页面上看不出来。
        String body = """
                {"success":true,"result":{"count":3,"data":[
                  {"SECURITY_NAME_ABBR":"某票","TRADE_DATE":"2026-09-30 00:00:00",
                   "PE_TTM":"-","PB_MRQ":1.0,"CLOSE_PRICE":10.0},
                  {"SECURITY_NAME_ABBR":"某票","TRADE_DATE":"2026-09-29 00:00:00",
                   "PE_TTM":12.0,"PB_MRQ":1.0,"CLOSE_PRICE":9.9},
                  {"SECURITY_NAME_ABBR":"某票","TRADE_DATE":"2026-09-28 00:00:00",
                   "PE_TTM":11.0,"PB_MRQ":1.0,"CLOSE_PRICE":9.8}
                ]}}""";
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(body, PLAIN_UTF8));

        StockValuationClient.History h = client().fetchHistory("300274");

        assertThat(h.rawCount()).isEqualTo(3);
        assertThat(h.points()).hasSize(3);                       // 行还在：PB 与收盘价仍可用
        assertThat(h.points().get(2).peTtm()).isNull();           // 但 PE 是 null，不是 0
        // 这一行**留在了 points 里**（PB、收盘价仍可用），却要从 PE 窗口里排除，
        // 所以单独报 1 天。拿 rawCount - points.size() 会得 0——那数的是「交易日读不出来的行」。
        assertThat(h.daysWithoutPe()).isEqualTo(1);
    }

    @Test
    void aDuplicateTradingDayIsCountedOnceSoTheWindowDoesNotGrow() {
        // 同一天两行会让窗口多一个点，分位分母因此变大——差值很小，页面上永远看不出来。
        String body = """
                {"success":true,"result":{"count":3,"data":[
                  {"SECURITY_NAME_ABBR":"某票","TRADE_DATE":"2026-09-30 00:00:00",
                   "PE_TTM":15.0,"PB_MRQ":1.0,"CLOSE_PRICE":10.0},
                  {"SECURITY_NAME_ABBR":"某票","TRADE_DATE":"2026-09-30 00:00:00",
                   "PE_TTM":14.0,"PB_MRQ":1.0,"CLOSE_PRICE":10.0},
                  {"SECURITY_NAME_ABBR":"某票","TRADE_DATE":"2026-09-29 00:00:00",
                   "PE_TTM":13.0,"PB_MRQ":1.0,"CLOSE_PRICE":9.9}
                ]}}""";
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(body, PLAIN_UTF8));

        StockValuationClient.History h = client().fetchHistory("300274");

        assertThat(h.points()).hasSize(2);
        // 保留的是**后出现**的那行（服务端降序，即当天更新的那条）
        assertThat(h.points().get(1).peTtm()).isEqualByComparingTo("14.0");
    }

    @Test
    void anEmptyOrBusinessErrorResponseIsAFailureNotAnEmptyHistory() {
        // 「0 行」与「有行但都没 PE」是两件事：前者要换代码，后者要等披露。
        // 返回一个空列表会让页面把两者都说成「暂无分位」。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess("{\"success\":true,\"result\":{\"count\":0,\"data\":[]}}",
                        PLAIN_UTF8));
        StockValuationClient.History empty = client().fetchHistory("300274");
        assertThat(empty.ok()).isFalse();
        assertThat(empty.failureReason()).contains("没有这只票的记录");

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess("{\"success\":false,\"message\":\"参数预处理错误\"}", PLAIN_UTF8));
        StockValuationClient.History errored = client().fetchHistory("300274");
        assertThat(errored.ok()).isFalse();
    }

    // ------------------------------------------------------------------
    // 限流：唯一的例外，必须抛出去让上层进冷却
    // ------------------------------------------------------------------

    @Test
    void aThrottledResponseIsRaisedNotSwallowedAndNeverRetried() {
        // 东财按 IP 计数，重试只会把封禁推得更深。必须原样抛给上层 → 冷却 → HTTP 429。
        // 这里只登记一个响应：如果客户端试图换域名或重试，第二次请求会没有响应可匹配而失败。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withException(new SocketException("Connection reset by peer")));

        assertThatThrownBy(() -> client().fetchHistory("300274"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
        server.verify();
    }

    @Test
    void anOrdinaryNetworkFailureIsReportedInTheReturnValueNotThrown() {
        // 与上一条对照：连不上不是「被拒绝」。抛出去会让整页变成错误页，
        // 而这只影响 PE 分位那一块，价格照常能看。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withException(new java.net.UnknownHostException("datacenter-web")));

        StockValuationClient.History h = client().fetchHistory("300274");

        assertThat(h.ok()).isFalse();
        assertThat(h.failureReason()).contains("估值分析源不可达");
        assertThat(h.points()).isEmpty();
    }
}