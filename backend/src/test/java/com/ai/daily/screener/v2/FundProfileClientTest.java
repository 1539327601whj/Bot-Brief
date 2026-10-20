package com.ai.daily.screener.v2;

import com.ai.daily.config.MarketHttpClientConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 基金档案解析。fixture 是 513100 的真实页面原文（2026-10 抓取），
 * 保留了 jbgk 页省略 {@code </td>} 的坏标签形态——解析器就是为它写的。
 */
class FundProfileClientTest {

    private static final MediaType PLAIN_UTF8 =
            MediaType.parseMediaType("text/plain;charset=UTF-8");
    private static final String INFO_URL =
            "https://fundf10.eastmoney.com/jbgk_513100.html";
    private static final String TRACKING_URL =
            "https://fundf10.eastmoney.com/tsdata_513100.html";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        restTemplate = new MarketHttpClientConfig()
                .marketRestTemplate(Duration.ofSeconds(5), Duration.ofSeconds(10));
        server = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void parsesScaleFeesAndTrackingErrorFromRealPages() throws IOException {
        expectPage(INFO_URL, "fund/jbgk_513100.html");
        expectPage(TRACKING_URL, "fund/tsdata_513100.html");

        FundProfileClient.Result result = new FundProfileClient(restTemplate).fetch("513100");

        assertThat(result.failed()).isFalse();
        assertThat(result.note()).isNull();
        FactsSnapshot.FundProfile p = result.profile();
        assertThat(p).isNotNull();
        assertThat(p.name()).isEqualTo("纳指ETF国泰");
        assertThat(p.fullName()).contains("纳斯达克100交易型开放式指数证券投资基金");
        assertThat(p.company()).isEqualTo("国泰基金");
        assertThat(p.custodian()).isEqualTo("建设银行");
        assertThat(p.manager()).isEqualTo("艾小军");
        assertThat(p.inceptionDate()).isEqualTo(LocalDate.of(2013, 4, 25));
        assertThat(p.inceptionScale()).isEqualTo("2.676亿份");
        // 194.68 亿元 → 元；打分时才除 1e8。
        assertThat(p.aum()).isEqualByComparingTo("19468000000");
        assertThat(p.aumAsOf()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(p.managementFeePct()).isEqualByComparingTo("0.60");
        assertThat(p.custodyFeePct()).isEqualByComparingTo("0.20");
        assertThat(p.trackingTarget()).isEqualTo("纳斯达克100指数");
        assertThat(p.trackingErrorPct()).isEqualByComparingTo("1.02");
        assertThat(p.peerTrackingErrorPct()).isEqualByComparingTo("2.21");
        assertThat(p.trackingErrorAsOf()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(p.source()).isEqualTo(FundProfileClient.SOURCE_LABEL);
        server.verify();
    }

    @Test
    void keepsProfileWhenTrackingPageFails() throws IOException {
        expectPage(INFO_URL, "fund/jbgk_513100.html");
        server.expect(requestTo(TRACKING_URL)).andRespond(withServerError());

        FundProfileClient.Result result = new FundProfileClient(restTemplate).fetch("513100");

        assertThat(result.failed()).isFalse();
        assertThat(result.profile()).isNotNull();
        assertThat(result.profile().aum()).isEqualByComparingTo("19468000000");
        assertThat(result.profile().trackingErrorPct()).isNull();
        assertThat(result.note()).contains("跟踪误差");
        server.verify();
    }

    @Test
    void failedPagesAreNegativelyCached() {
        server.expect(requestTo(INFO_URL)).andRespond(withServerError());
        server.expect(requestTo(TRACKING_URL)).andRespond(withServerError());
        FundProfileClient client = new FundProfileClient(restTemplate);

        FundProfileClient.Result first = client.fetch("513100");
        assertThat(first.failed()).isTrue();
        assertThat(first.profile()).isNull();

        // TTL 内第二次：一次请求都不该再发；多发的请求会让 MockRestServiceServer 直接失败。
        FundProfileClient.Result second = client.fetch("513100");
        assertThat(second.failed()).isTrue();
        server.verify();
    }

    private void expectPage(String url, String classpath) throws IOException {
        byte[] body = new ClassPathResource(classpath).getInputStream().readAllBytes();
        server.expect(requestTo(url)).andRespond(withSuccess(body, PLAIN_UTF8));
    }
}
