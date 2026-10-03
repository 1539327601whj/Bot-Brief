package com.ai.daily.screener;

import com.ai.daily.config.MarketHttpClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 兜底行情源（腾讯 / 新浪）。照 {@link MarketDataClientHttpTest} 走**真实的转换器链**：
 * MockRestServiceServer 只换掉底层连接，解码那一行仍是我们生产里那一行。
 *
 * <p>这样才有意义——这里要验的核心恰恰是「字节怎么变成中文」，
 * 用 mock 掉的 RestTemplate 测等于把被验的东西替换掉了。
 *
 * <h2>为什么字符集断言在这两个源上必须反过来写</h2>
 *
 * <p>东财那边（{@link MarketDataClientHttpTest}）的规则是：服务端声明了 charset 就听它的，
 * 没声明才按 UTF-8。腾讯与新浪**恰好相反**——它们不声明 charset，内容是 GBK。
 * 所以这里的断言是「**即使响应头写的是 UTF-8，也必须按 GBK 解**」：
 * 一律强制，不 sniff。默认值错在哪边都是静默的（乱码能通过后面所有字符串解析），
 * 所以两边都得有一条会红的测试盯着，而不是靠注释提醒。
 */
class MarketDataClientFallbackTest {

    /** 腾讯声明了 GBK。 */
    private static final MediaType PLAIN_GBK = MediaType.parseMediaType("text/plain;charset=GBK");

    /** 腾讯什么都不声明——**线上实测就是这个形态**，也是默认值最容易出错的那种。 */
    private static final MediaType PLAIN_NO_CHARSET = MediaType.parseMediaType("text/plain");

    /** 假装服务端把 charset 写错了。强制 GBK 的规则下，它不该影响结果。 */
    private static final MediaType PLAIN_WRONG_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private static final Charset GBK = Charset.forName("GBK");

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private AltQuoteSource alt;

    @BeforeEach
    void setUp() {
        restTemplate = new MarketHttpClientConfig()
                .marketRestTemplate(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(10));
        server = MockRestServiceServer.createServer(restTemplate);
        alt = new AltQuoteSource(restTemplate);
    }

    // ------------------------------------------------------------------
    // 造响应
    // ------------------------------------------------------------------

    /**
     * 88 个字段，位置照实测。{@code ~} 分隔，与线上一致。
     *
     * <p>字段数不是随手写的 88：解析器会拿它当形状校验，位置对不上时宁可把
     * 涨跌幅/成交额/市值按缺失处理，也不去读一个可能已经挪位的位置。
     */
    private static String[] tencentFields(String name, String code, String price, String prevClose,
                                          String pctChange, String amountWan, String capYi) {
        String[] f = new String[AltQuoteSource.TENCENT_FIELD_COUNT];
        Arrays.fill(f, "");
        f[AltQuoteSource.TENCENT_NAME] = name;
        f[AltQuoteSource.TENCENT_CODE] = code;
        f[AltQuoteSource.TENCENT_PRICE] = price;
        f[AltQuoteSource.TENCENT_PREV_CLOSE] = prevClose;
        f[AltQuoteSource.TENCENT_PCT_CHANGE] = pctChange;
        f[AltQuoteSource.TENCENT_AMOUNT_WAN] = amountWan;
        f[AltQuoteSource.TENCENT_MARKET_CAP_YI] = capYi;
        return f;
    }

    private static String tencentStatement(String symbol, String... fields) {
        return "v_" + symbol + "=\"" + String.join("~", fields) + "\";";
    }

    /** 510300 的**实测值**（2026-09-30 现场取的），单位换算的断言就靠它。 */
    private static String hs300Statement() {
        return tencentStatement("sh510300",
                tencentFields("沪深300ETF华泰柏瑞", "510300", "4.432", "4.416", "0.36",
                        "219623", "1068.98"));
    }

    private static String chuangyeStatement() {
        return tencentStatement("sz159915",
                tencentFields("创业板ETF", "159915", "2.512", "2.498", "0.56",
                        "180000", "653.84"));
    }

    private static byte[] gbk(String body) {
        return body.getBytes(GBK);
    }

    // ------------------------------------------------------------------
    // 字符集：错了不报错，只静默出乱码
    // ------------------------------------------------------------------

    @Test
    void chineseEtfNamesSurviveWhenTencentDeclaresNoCharsetAtAll() {
        // 线上实测：腾讯什么都不声明。此时 Spring 默认按 ISO-8859-1 读，中文必乱。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(hs300Statement()), PLAIN_NO_CHARSET));

        Map<String, MarketDataClient.EtfQuote> quotes = alt.fetchTencentQuotes(List.of("510300"));

        // 乱码**能通过**字符串解析：分隔符全是 ASCII。所以只断言「拿得到一只」是拦不住的，
        // 必须逐字断名字。
        assertThat(quotes.get("510300").name()).isEqualTo("沪深300ETF华泰柏瑞");
    }

    @Test
    void aCharsetHeaderIsIgnoredBecauseTheseSourcesAreAlwaysGbk() {
        // 与东财相反：那边「服务端声明了就得听」；这边一律强制 GBK。
        // 服务端把 charset 写成 UTF-8（真实情况里 GB2312/GBK/不写都有）时，
        // 听它的反而会把名字解坏。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(hs300Statement()), PLAIN_WRONG_UTF8));

        Map<String, MarketDataClient.EtfQuote> quotes = alt.fetchTencentQuotes(List.of("510300"));

        assertThat(quotes.get("510300").name()).isEqualTo("沪深300ETF华泰柏瑞");
    }

    @Test
    void aDeclaredGbkIsAlsoFine() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(hs300Statement()), PLAIN_GBK));

        assertThat(alt.fetchTencentQuotes(List.of("510300")).get("510300").name())
                .isEqualTo("沪深300ETF华泰柏瑞");
    }

    @Test
    void sinaNamesSurviveTheSameWay() {
        String body = "var hq_str_sh510300=\"沪深300ETF华泰柏瑞,4.421,4.416,4.432,4.444,4.415,"
                + "4.432,4.433,495854381,2196229027.000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,"
                + "2026-09-30,15:34:59,00\";";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("hq.sinajs.cn/list=")))
                .andRespond(withSuccess(gbk(body), PLAIN_NO_CHARSET));

        Map<String, MarketDataClient.EtfQuote> quotes = alt.fetchSinaQuotes(List.of("510300"));

        assertThat(quotes.get("510300").name()).isEqualTo("沪深300ETF华泰柏瑞");
        assertThat(quotes.get("510300").provider()).isEqualTo(AltQuoteSource.PROVIDER_SINA);
    }

    // ------------------------------------------------------------------
    // 单位：两个源必须给出同一个单位，否则同一格差一万倍而看不出来
    // ------------------------------------------------------------------

    @Test
    void tencentAmountAndScaleAreConvertedToYuanLikeEastmoney() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(hs300Statement()), PLAIN_NO_CHARSET));

        MarketDataClient.EtfQuote q = alt.fetchTencentQuotes(List.of("510300")).get("510300");

        // 成交额：[37] 是万元
        assertThat(q.amount()).isEqualByComparingTo("2196230000");
        // 总市值：[45] 是亿元
        assertThat(q.marketCap()).isEqualByComparingTo("106898000000");
        assertThat(q.price()).isEqualByComparingTo("4.432");
        assertThat(q.pctChange()).isEqualByComparingTo("0.36");
        assertThat(q.provider()).isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
    }

    @Test
    void sinaAmountIsAlreadyInYuanAndIsNotRescaled() {
        String body = "var hq_str_sh510300=\"沪深300ETF华泰柏瑞,4.421,4.416,4.432,4.444,4.415,"
                + "4.432,4.433,495854381,2196229027.000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,"
                + "2026-09-30,15:34:59,00\";";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("hq.sinajs.cn/list=")))
                .andRespond(withSuccess(gbk(body), PLAIN_NO_CHARSET));

        MarketDataClient.EtfQuote q = alt.fetchSinaQuotes(List.of("510300")).get("510300");

        // 新浪 [9] 本来就是元，再乘一次 10000 就差了四个数量级
        assertThat(q.amount()).isEqualByComparingTo("2196229027.000");
        assertThat(q.price()).isEqualByComparingTo("4.432");
        // 新浪不给规模，也不给 PE。缺失必须是 null——不许为了「不留空」兜 0
        assertThat(q.marketCap()).isNull();
        assertThat(q.peTtm()).isNull();
        assertThat(q.pb()).isNull();
    }

    @Test
    void sinaChangeIsDerivedFromTheTwoPricesItReports() {
        String body = "var hq_str_sh510300=\"沪深300ETF华泰柏瑞,4.421,4.416,4.432,4.444,4.415,"
                + "4.432,4.433,495854381,2196229027.000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,"
                + "2026-09-30,15:34:59,00\";";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("hq.sinajs.cn/list=")))
                .andRespond(withSuccess(gbk(body), PLAIN_NO_CHARSET));

        MarketDataClient.EtfQuote q = alt.fetchSinaQuotes(List.of("510300")).get("510300");

        // (4.432-4.416)/4.416 = 0.3623% —— 与腾讯独立给出的 0.36 一致
        assertThat(q.pctChange()).isEqualByComparingTo("0.36");
    }

    @Test
    void aZeroPreviousCloseYieldsNoChangeRatherThanADivisionByZero() {
        assertThat(AltQuoteSource.derivedPctChange(new BigDecimal("4.432"), BigDecimal.ZERO)).isNull();
        assertThat(AltQuoteSource.derivedPctChange(new BigDecimal("4.432"), null)).isNull();
    }

    // ------------------------------------------------------------------
    // 缺失就是缺失，不能变成 0，也不能张冠李戴
    // ------------------------------------------------------------------

    @Test
    void aSymbolTencentSilentlyDropsIsMissingNotZero() {
        // 腾讯对不认识的代码**整条语句都不回**。请求两只、只回一只时，
        // 缺的那只必须是不在 Map 里，而不是一条价格 0 的记录。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(hs300Statement()), PLAIN_NO_CHARSET));

        Map<String, MarketDataClient.EtfQuote> quotes =
                alt.fetchTencentQuotes(List.of("510300", "159915"));

        assertThat(quotes).containsOnlyKeys("510300");
        assertThat(quotes).doesNotContainKey("159915");
    }

    @Test
    void anEmptySinaPayloadIsAlsoMissing() {
        // 新浪对不认识的代码回的是空串，与腾讯「整条不回」不同，但同样是缺失
        String body = "var hq_str_sh510300=\"\";";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("hq.sinajs.cn/list=")))
                .andRespond(withSuccess(gbk(body), PLAIN_NO_CHARSET));

        assertThat(alt.fetchSinaQuotes(List.of("510300"))).isEmpty();
    }

    @Test
    void aChangedFieldCountMakesThePositionalFieldsMissingInsteadOfWrong() {
        // 腾讯加一列就会让 [37]/[45] 指向别的数。那是「读到了值、但是错的值」，
        // 比 null 危险得多——页面上完全看不出来。
        String[] shortFields = tencentFields("沪深300ETF华泰柏瑞", "510300", "4.432", "4.416",
                "0.36", "219623", "1068.98");
        String body = tencentStatement("sh510300", Arrays.copyOf(shortFields, 60));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(body), PLAIN_NO_CHARSET));

        MarketDataClient.EtfQuote q = alt.fetchTencentQuotes(List.of("510300")).get("510300");

        // [1]–[5] 这段加了列也不会动，仍然可信
        assertThat(q.name()).isEqualTo("沪深300ETF华泰柏瑞");
        assertThat(q.price()).isEqualByComparingTo("4.432");
        // 位置相关的三个一律按缺失处理
        assertThat(q.pctChange()).isNull();
        assertThat(q.amount()).isNull();
        assertThat(q.marketCap()).isNull();
    }

    @Test
    void anUnrecognisedMarketPrefixIsSkippedRatherThanGuessed() {
        // 判断不出市场就不能默认给个 sh：前缀写错的后果是两个源都静默丢代码，
        // 表现成「这一只就是没有行情」，看不出原因。
        assertThat(AltQuoteSource.tencentSymbol("510300")).isEqualTo("sh510300");
        assertThat(AltQuoteSource.tencentSymbol("159915")).isEqualTo("sz159915");
        assertThat(AltQuoteSource.tencentSymbol("600519")).isNull();
        assertThat(AltQuoteSource.tencentSymbol("51030")).isNull();
        assertThat(AltQuoteSource.tencentSymbol(null)).isNull();

        // 一个都认不出来时不该发请求（发了也是白发，还要记一次外呼）
        assertThat(alt.fetchTencentQuotes(List.of("600519"))).isEmpty();
        server.verify();
    }

    // ------------------------------------------------------------------
    // 日线：与东财共用同一份解析
    // ------------------------------------------------------------------

    @Test
    void tencentKlineIsParsedByTheSameParserAsEastmoney() {
        // 真实形状：data.<sym>.qfqday 是数组的数组，[0]=日期 [1]=开 [2]=收
        String body = "{\"code\":0,\"msg\":\"\",\"data\":{\"sh510300\":{\"qfqday\":["
                + "[\"2026-09-28\",\"4.620\",\"4.590\",\"4.621\",\"4.586\",\"6364575.000\"],"
                + "[\"2026-09-29\",\"4.578\",\"4.515\",\"4.579\",\"4.512\",\"7102519.000\"],"
                + "[\"2026-09-30\",\"4.421\",\"4.432\",\"4.444\",\"4.415\",\"4958544.000\"]],"
                + "\"qt\":{},\"prec\":\"4.515\",\"version\":\"3\"}}}";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("fqkline/get")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        MarketDataClient.KlineOutcome outcome = alt.fetchTencentKline("510300", 250);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.provider()).isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
        // 收在 idx2 —— 与东财 CSV 的 idx2 是同一个位置，所以两边共用一份解析
        assertThat(outcome.bars()).extracting(PricePositionCalculator.Bar::close)
                .containsExactly(new BigDecimal("4.590"), new BigDecimal("4.515"), new BigDecimal("4.432"));
        assertThat(outcome.bars().get(0).date()).isEqualTo(java.time.LocalDate.of(2026, 9, 28));
    }

    @Test
    void theKlineRequestKeepsItsCommasAndTheAdjustmentFlag() {
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("param=sh510300,day,,,250,qfq"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("%2C")))))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        alt.fetchTencentKline("510300", 250);

        server.verify();
    }

    /**
     * 带日期区间的那次外呼：{@code end} 落在 param 的**第二个空档位**上，逗号与空档位原样保留。
     *
     * <p>这一条是翻页的地基：腾讯只给 {@code end} 不给 {@code start} 时返回的是**该区间末尾**的
     * N 根，{@code CodeLookupService} 就是靠它一页页往回翻到十年的。位置写错（比如贴到
     * {@code start} 那一格）不会报错——源会照给最近 N 根，于是翻页「每次都回同一批」，
     * 表现成「只能看到最近三年」，而这正是这次要修的那个缺口。
     */
    @Test
    void theKlineRequestPutsTheEndDateInItsOwnSlotAndKeepsTheCommas() {
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("param=sh510300,day,,2023-06-14,800,qfq"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("%2C")))))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        alt.fetchTencentKline("510300", java.time.LocalDate.of(2023, 6, 14), 800);

        server.verify();
    }

    @Test
    void aKlineWithNoRowsIsAFailureWithAReasonNotAnEmptySuccess() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("fqkline/get")))
                .andRespond(withSuccess("{\"code\":0,\"data\":{}}", MediaType.APPLICATION_JSON));

        MarketDataClient.KlineOutcome outcome = alt.fetchTencentKline("510300", 250);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.failureReason()).contains("价格位置未确认");
    }

    @Test
    void parseDelimitedBarsAcceptsBothShapes() {
        // 东财给 CSV 字符串，腾讯给数组。字段位置一致，所以共用一份实现——
        // 腾讯分支另写一套的话，两边会在某个「close ≤ 0 跳过」之类的细节上悄悄分叉。
        List<PricePositionCalculator.Bar> fromCsv = MarketDataClient.parseDelimitedBars(
                List.of("2026-09-30,4.421,4.432,4.444,4.415,4958544,219623,0.66"));
        List<PricePositionCalculator.Bar> fromArrays = MarketDataClient.parseDelimitedBars(
                List.of(List.of("2026-09-30", "4.421", "4.432", "4.444", "4.415", "4958544.000")));

        assertThat(fromCsv).isEqualTo(fromArrays);
        assertThat(fromCsv).hasSize(1);
    }

    @Test
    void parseDelimitedBarsSkipsBadRowsWithoutLosingTheGoodOnes() {
        List<PricePositionCalculator.Bar> bars = MarketDataClient.parseDelimitedBars(List.of(
                "2026-09-28,4.620,4.590,4.621,4.586,1,1,1",
                "2026-09-29,4.578,0,4.579,4.512,1,1,1",        // close = 0 必须跳过，不能当真实价
                "2026-09-30,4.421,4.432,4.444,4.415,1,1,1",
                "坏日期,4.421,4.432,4.444,4.415,1,1,1",
                "2026-10-01,4.421"));                           // 列不够

        assertThat(bars).extracting(PricePositionCalculator.Bar::close)
                .containsExactly(new BigDecimal("4.590"), new BigDecimal("4.432"));
    }

    // ------------------------------------------------------------------
    // 限流：必须让上层知道，好按源进冷却
    // ------------------------------------------------------------------

    @Test
    void aThrottledTencentRaisesTheRateLimitedSignalInsteadOfReturningEmpty() {
        // 「服务端建了连接却一个字节都不回」。返回空 Map 会把这个信号吞掉，
        // 上层就没法只为腾讯记一次冷却，也就等于把兜底源也一起锁死。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withException(new SocketException("Connection reset")));

        assertThatThrownBy(() -> alt.fetchTencentQuotes(List.of("510300")))
                .isInstanceOf(MarketDataException.MarketDataRateLimitedException.class);
    }

    @Test
    void aThrottledTencentKlineIsReportedAsThrottledRatherThanMerelyFailed() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("fqkline/get")))
                .andRespond(withException(new SocketException("Connection reset")));

        MarketDataClient.KlineOutcome outcome = alt.fetchTencentKline("510300", 250);

        assertThat(outcome.throttled()).isTrue();
        assertThat(outcome.provider()).isEqualTo(AltQuoteSource.PROVIDER_TENCENT);
    }

    @Test
    void batchingSplitsLongListsButKeepsEveryCode() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String code = "51030" + i;
            body.append(tencentStatement("sh" + code,
                    tencentFields("基金" + i, code, "1.000", "1.000", "0", "1", "1")));
        }
        server.expect(requestTo(org.hamcrest.Matchers.containsString("qt.gtimg.cn/q=")))
                .andRespond(withSuccess(gbk(body.toString()), PLAIN_NO_CHARSET));

        Map<String, MarketDataClient.EtfQuote> quotes =
                alt.fetchTencentQuotes(List.of("510300", "510301", "510302"));

        assertThat(quotes).containsOnlyKeys("510300", "510301", "510302");
    }
}