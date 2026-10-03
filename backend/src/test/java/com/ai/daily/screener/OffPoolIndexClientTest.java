package com.ai.daily.screener;

import com.ai.daily.config.MarketHttpClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
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
 * {@link OffPoolIndexClient} 的测试，走真实转换器链。
 *
 * <p>盯住的是四个**静默失败**——它们都不报错，只是数字变了或页面空了：
 * <ol>
 *   <li>中证官网的字段叫 {@code peg}，不叫 {@code pe}/{@code peTtm}；</li>
 *   <li>{@code tradeDate} 是紧凑的 {@code YYYYMMDD}，不是 ISO——写成破折号形态会解析失败，
 *       而失败是被 {@code continue} 掉的，表现成「这个指数没有历史」；</li>
 *   <li>蛋卷的 {@code pe_percentile} 是 0–1，不乘 100 会得到「分位 0.4%」；</li>
 *   <li>蛋卷 {@code pe=0} 是「没有数据」，不是「PE 等于 0」。</li>
 * </ol>
 */
class OffPoolIndexClientTest {

    private static final MediaType PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    /** 真实形态：紧凑日期 + {@code peg} 字段。 */
    private static final String CSINDEX_BODY = """
            {"code":"200","success":true,"msg":"成功","data":[
              {"tradeDate":"20110628","peg":20.11},
              {"tradeDate":"20110629","peg":20.55},
              {"tradeDate":"20260929","peg":13.40},
              {"tradeDate":"20260930","peg":13.55}
            ]}""";

    private static final String DANJUAN_BODY = """
            {"result_code":0,"data":{"items":[
              {"index_code":"NDX","name":"纳斯达克100","pe":35.2,"pe_percentile":0.8732,
               "ts":1780000000000},
              {"index_code":"SP500","name":"标普500","pe":28.1,"pe_percentile":0.9105,
               "ts":1780000000000},
              {"index_code":"GDAXI","name":"德国DAX","pe":0,"pe_percentile":0,"ts":1780000000000}
            ]}}""";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private OffPoolIndexClient client() {
        return new OffPoolIndexClient(restTemplate);
    }

    private OffPoolIndexClient.Result fetchCsindex(String code) {
        return client().fetch(OffPoolIndexResolver.resolve(code).orElseThrow());
    }

    // ------------------------------------------------------------------
    // 中证官网
    // ------------------------------------------------------------------

    @Test
    void theCsindexPeColumnIsCalledPegNotPe() {
        // 字段名改了不会有任何报错，只会一夜之间所有中证系指数都「没有历史」。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("indexCode=000300")))
                .andRespond(withSuccess(CSINDEX_BODY, PLAIN_UTF8));

        OffPoolIndexClient.Result r = fetchCsindex("000300");

        assertThat(r.ok()).isTrue();
        assertThat(r.currentPe()).isEqualByComparingTo("13.55");
        assertThat(r.points()).hasSize(4);
    }

    @Test
    void compactTradeDatesAreParsedAndDashedOnesAreNotSilentlyAccepted() {
        // 中证官网给的是 20110628。这条在 Python 侧踩过一次：mock 用破折号形态，
        // 于是格式变了也测不出来，线上表现是「未返回有效PE历史」——看着像源没数据。
        assertThat(OffPoolIndexClient.csindexDay("20110628")).isEqualTo(LocalDate.of(2011, 6, 28));
        assertThat(OffPoolIndexClient.csindexDay("2011-06-28")).isNull();
        assertThat(OffPoolIndexClient.csindexDay("")).isNull();
        assertThat(OffPoolIndexClient.csindexDay(null)).isNull();

        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(CSINDEX_BODY, PLAIN_UTF8));
        assertThat(fetchCsindex("000300").points().get(0).date()).isEqualTo(LocalDate.of(2011, 6, 28));
    }

    @Test
    void theHistoryComesBackAscendingAndItsPercentileIsComputed() {
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(CSINDEX_BODY, PLAIN_UTF8));

        OffPoolIndexClient.Result r = fetchCsindex("000300");

        assertThat(r.points()).extracting(RollingPercentile.Point::date)
                .containsExactly(LocalDate.of(2011, 6, 28), LocalDate.of(2011, 6, 29),
                        LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 30));
        // 末点是窗口里的最大值 → 100
        assertThat(r.currentPercentile()).isEqualByComparingTo("100.0000");
        assertThat(r.percentileMethod()).isEqualTo(IndexFundPool.METHOD_CSINDEX_ROLLING_10Y);
        assertThat(r.hasHistory()).isTrue();
    }

    @Test
    void badPeValuesAndFutureDatesAreDroppedRatherThanBecomingExtremes() {
        // 一个未来日期会让「今天」变成未来；一个 0 或超上限的 PE 会算成「史上最便宜」。
        String body = """
                {"code":"200","success":true,"data":[
                  {"tradeDate":"20260929","peg":13.40},
                  {"tradeDate":"20260930","peg":0},
                  {"tradeDate":"20260930","peg":-1},
                  {"tradeDate":"20260930","peg":9999},
                  {"tradeDate":"20991231","peg":14.0}
                ]}""";
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(body, PLAIN_UTF8));

        OffPoolIndexClient.Result r = fetchCsindex("000300");

        assertThat(r.points()).hasSize(1);
        assertThat(r.currentPe()).isEqualByComparingTo("13.40");
    }

    @Test
    void aBusinessErrorEnvelopeIsAFailureNotAnEmptyHistory() {
        // 不看 code/success 的话，业务错误会被当成「0 个数据点」，
        // 页面于是说「该指数没有历史」——而真实原因可能是参数写错了。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess("{\"code\":\"500\",\"success\":false,\"msg\":\"参数错误\"}",
                        PLAIN_UTF8));

        OffPoolIndexClient.Result r = fetchCsindex("000300");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("业务错误");
        assertThat(r.points()).isEmpty();
    }

    @Test
    void aCodeThatSourceDoesNotCoverSaysSoInsteadOfFallingBackToTheOtherSource() {
        // 中证官网不覆盖深证系。这里必须**只说拿不到**，绝不转去蛋卷取一个数来填——
        // 跨口径的数实测能差 22%–75%，而页面上看不出来。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess("{\"code\":\"200\",\"success\":true,\"data\":[]}", PLAIN_UTF8));

        OffPoolIndexClient.Result r = fetchCsindex("399006");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("没有").contains("399006");
        assertThat(r.source()).isEqualTo(IndexFundPool.SOURCE_CSINDEX);
        server.verify();   // 只打了一次，没有第二次去别的源
    }

    @Test
    void onlyTheCsindexDomainIsTouchedForACsindexRoute() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.csindex.com.cn/")))
                .andRespond(withSuccess(CSINDEX_BODY, PLAIN_UTF8));

        fetchCsindex("000300");
        server.verify();
    }

    // ------------------------------------------------------------------
    // 蛋卷
    // ------------------------------------------------------------------

    @Test
    void theDanjuanPercentileRatioIsScaledToAPercentage() {
        // pe_percentile 是 0–1。不乘 100 会得到「分位 0.87%」，页面读起来像「极度低估」。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/djapi/index_eva/dj")))
                .andRespond(withSuccess(DANJUAN_BODY, PLAIN_UTF8));

        OffPoolIndexClient.Result r =
                client().fetch(OffPoolIndexResolver.resolve("NDX").orElseThrow());

        assertThat(r.ok()).isTrue();
        assertThat(r.currentPe()).isEqualByComparingTo("35.2");
        assertThat(r.currentPercentile()).isEqualByComparingTo("87.32");
        assertThat(r.percentileMethod()).isEqualTo(IndexFundPool.METHOD_DANJUAN);
        assertThat(r.label()).isEqualTo(OffPoolIndexResolver.LABEL_DANJUAN);
    }

    @Test
    void danjuanHasNoHistorySoTheLongLookbacksMustComeBackEmpty() {
        // 蛋卷只给当前值。points 为空是**有意的表达**：填一个单点序列会让调用方
        // 以为「有历史、只是短」，而真相是「这个源根本不提供历史」——这话不一样。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(DANJUAN_BODY, PLAIN_UTF8));

        OffPoolIndexClient.Result r =
                client().fetch(OffPoolIndexResolver.resolve("NDX").orElseThrow());

        assertThat(r.hasHistory()).isFalse();
        assertThat(r.points()).isEmpty();
    }

    @Test
    void danjuanPeOfZeroMeansNoDataNotAPeOfZero() {
        // 实测蛋卷确实有 pe=0 的条目。当成真实的 0 会算出一个不存在的「史上最便宜」。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(DANJUAN_BODY, PLAIN_UTF8));

        OffPoolIndexClient.Result r =
                client().fetch(OffPoolIndexResolver.resolve("GDAXI").orElseThrow());

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("GDAXI");
    }

    @Test
    void theDanjuanLookupIsCaseInsensitiveBecauseTheRouteUppercasesIt() {
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(DANJUAN_BODY, PLAIN_UTF8));

        // 用户输小写也要查得到
        assertThat(client().fetch(OffPoolIndexResolver.resolve("ndx").orElseThrow()).ok()).isTrue();
    }

    @Test
    void anIndexMissingFromTheDanjuanListSaysHowManyWereSearched() {
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess(DANJUAN_BODY, PLAIN_UTF8));

        OffPoolIndexClient.Result r =
                client().fetch(OffPoolIndexResolver.resolve("NOTHERE").orElseThrow());

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("3").contains("NOTHERE");
    }

    @Test
    void aDanjuanBusinessErrorIsAFailureNotAnEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withSuccess("{\"result_code\":\"-1\",\"result_msg\":\"服务繁忙\"}", PLAIN_UTF8));

        assertThat(client().fetch(OffPoolIndexResolver.resolve("NDX").orElseThrow()).ok()).isFalse();
    }

    @Test
    void theDanjuanTimestampIsReadInBeijingTimeAndFallsBackToTheDateField() {
        // ts 是 epoch **毫秒**。当秒读会得到 1970 年，于是「今日 PE」挂在一个 56 年前的日期上。
        java.time.Instant instant = java.time.Instant.ofEpochMilli(1780000000000L);
        LocalDate expected = instant.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
        assertThat(OffPoolIndexClient.danjuanDay(java.util.Map.of("ts", 1780000000000L)))
                .isEqualTo(expected);

        // 实测有条目只有 date 字段
        assertThat(OffPoolIndexClient.danjuanDay(java.util.Map.of("date", "2026-09-30")))
                .isEqualTo(LocalDate.of(2026, 9, 30));
        // 形态不认识就 null，不猜
        assertThat(OffPoolIndexClient.danjuanDay(java.util.Map.of("date", "30/09/2026"))).isNull();
        assertThat(OffPoolIndexClient.danjuanDay(java.util.Map.of())).isNull();
    }

    @Test
    void aDanjuanPercentileOutsideTheRatioRangeIsTreatedAsMissing() {
        assertThat(OffPoolIndexClient.danjuanPercentile(java.util.Map.of("pe_percentile", 0.5)))
                .isEqualByComparingTo("50.0");
        assertThat(OffPoolIndexClient.danjuanPercentile(java.util.Map.of("pe_percentile", 1.0)))
                .isEqualByComparingTo("100.0");
        // 已经是百分数了（>1）时不能再乘 100，宁可当缺失也不给出一个 8732% 的分位
        assertThat(OffPoolIndexClient.danjuanPercentile(java.util.Map.of("pe_percentile", 87.32)))
                .isNull();
        assertThat(OffPoolIndexClient.danjuanPercentile(java.util.Map.of("pe_percentile", -0.1)))
                .isNull();
        assertThat(OffPoolIndexClient.danjuanPercentile(java.util.Map.of())).isNull();
    }

    // ------------------------------------------------------------------
    // 限流
    // ------------------------------------------------------------------

    @Test
    void aThrottledResponseIsRaisedAndNeverRetriedAgainstTheOtherSource() {
        // 只登记一个响应：若客户端换了源或重试，第二次请求就匹配不上而失败。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withException(new SocketException("Connection reset by peer")));

        assertThatThrownBy(() -> fetchCsindex("000300"))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
        server.verify();
    }

    @Test
    void anOrdinaryNetworkFailureIsReportedInTheReturnValueNotThrown() {
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withException(new java.net.UnknownHostException("csindex.com.cn")));

        OffPoolIndexClient.Result r = fetchCsindex("000300");

        assertThat(r.ok()).isFalse();
        assertThat(r.failureReason()).contains("不可达");
    }
}