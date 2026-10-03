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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link MarketDataClient#fetchKline(String, int)} 那一个重载——「代码查询」页取十年日线的入口。
 *
 * <p>盯住三件事：
 * <ol>
 *   <li>新参数**真的**传到了 {@code lmt}（不传的话价格八档里五年/十年永远是空的，
 *       而页面看起来只是「历史不够」）；</li>
 *   <li>默认那一条**没有被改动**——筛选器一天要拉几百只，它必须继续只要 250 根；</li>
 *   <li>{@code fqt=1} 还在。前复权掉了会在除权日显示假跌 30%，把正常分红股判成「便宜」。</li>
 * </ol>
 *
 * <p>与 {@code MarketDataClientHttpTest} 一样走真实转换器链（{@link MockRestServiceServer} 只换底层连接），
 * 因为 push2his 这家的响应头声明同样不可信。
 */
class MarketDataClientLongKlineTest {

    private static final MediaType PLAIN_UTF8 = MediaType.parseMediaType("text/plain;charset=UTF-8");

    private static final String KLINE_BODY = """
            {"data":{"code":"300274","klines":[
              "2026-09-29,80.10,82.00,82.50,79.90,123456,1000000000.00,1.20",
              "2026-09-30,82.49,83.00,84.00,82.00,133456,1100000000.00,2.98"
            ]}}""";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private MarketDataClient client() {
        return new MarketDataClient(restTemplate, 250, 500, 50);
    }

    @Test
    void theExplicitLimitReachesTheRequestAsLmt() {
        // 断言请求原文里的 lmt=2600。这个参数走丢的话不会有任何报错——
        // 只会安静地只回 250 根，然后「五年」「十年」两档永远空着，看起来像数据源的问题。
        server.expect(requestTo(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("lmt=2600"),
                        org.hamcrest.Matchers.containsString("fqt=1"),
                        org.hamcrest.Matchers.containsString("secid=1.510300"))))
                .andRespond(withSuccess(KLINE_BODY, PLAIN_UTF8));

        MarketDataClient.KlineOutcome outcome = client().fetchKline("1.510300", 2600);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.bars()).hasSize(2);
        server.verify();
    }

    @Test
    void theNoArgOverloadStillAsksForTheScreenerDefaultAndNotTheLongOne() {
        // 筛选器一天拉几百只。让它跟着长档走，等于把盘后那一次的外呼量乘十倍——
        // 而出口 IP 是按请求数限流的，这正是章程最不想看到的那种「顺手改一下」。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("lmt=250")))
                .andRespond(withSuccess(KLINE_BODY, PLAIN_UTF8));

        client().fetchKline("1.510300");
        server.verify();
    }

    @Test
    void aZeroOrNegativeLimitIsClampedInsteadOfBeingSentAsIs() {
        // 0 或负数会被东财按「不限」处理并回一大坨，也可能直接报错。夹到 1 至少语义明确。
        server.expect(requestTo(org.hamcrest.Matchers.containsString("lmt=1")))
                .andRespond(withSuccess(KLINE_BODY, PLAIN_UTF8));
        client().fetchKline("1.510300", 0);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("lmt=1")))
                .andRespond(withSuccess(KLINE_BODY, PLAIN_UTF8));
        client().fetchKline("1.510300", -100);

        server.verify();
    }

    @Test
    void onlyTheKlineDomainIsTouched() {
        // 只登记了 push2his（KLINE_HOST）。真去打了 push2 或 datacenter-web，
        // 请求匹配不上会直接失败——比断言 URL 前缀更难绕过。
        server.expect(MockRestRequestMatchers.requestTo(
                        org.hamcrest.Matchers.startsWith("https://push2his.eastmoney.com/")))
                .andRespond(withSuccess(KLINE_BODY, PLAIN_UTF8));

        client().fetchKline("1.510300", 2600);
        server.verify();
    }

    @Test
    void aThrottledLongKlineComesBackAsThrottledAndNeverRetried() {
        // 只登记一个响应：换域名重试的话第二次请求没有响应可匹配。
        // 限流时重试只会把这个 IP 往更深的封禁里推。
        server.expect(requestTo(org.hamcrest.Matchers.anything()))
                .andRespond(withException(new SocketException("Connection reset by peer")));

        MarketDataClient.KlineOutcome outcome = client().fetchKline("1.510300", 2600);

        assertThat(outcome.throttled()).isTrue();
        assertThat(outcome.ok()).isFalse();
        server.verify();
    }
}