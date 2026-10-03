package com.ai.daily.screener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 出站拦截器：**先排队、再计数、打出去后按结果分类**。
 *
 * <p>闸门一律用间隔 0 的实例：这个类要测的是「记了什么、什么时候记」，
 * 不是排队本身（那是 {@link MarketRateLimitGateTest} 的事）。这样这一组用例
 * 一步都不睡，也不会因为将来有人改限速默认值而跟着变慢或变红。
 */
class MarketRateLimitInterceptorTest {

    private MarketCallMetrics metrics;
    private MarketRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        metrics = new MarketCallMetrics();
        interceptor = new MarketRateLimitInterceptor(new MarketRateLimitGate(0, 0), metrics);
    }

    private static HttpRequest requestTo(String url) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(url));
        return request;
    }

    private Map<String, Object> row(String provider) {
        return metrics.snapshot().get(provider);
    }

    @Test
    void aSuccessfulCallIsCountedAgainstItsProvider() throws Exception {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        ClientHttpRequestExecution execution = (req, body) -> response;

        ClientHttpResponse actual = interceptor.intercept(
                requestTo("https://push2.eastmoney.com/api/qt/ulist.np/get"), new byte[0], execution);

        assertThat(actual).isSameAs(response);
        assertThat(row("eastmoney")).containsEntry("calls", 1L).containsEntry("throttled", 0L);
    }

    @Test
    void theThreeEastmoneyDomainsAllLandInTheSameBucket() throws Exception {
        // 挂在模板上而不是各客户端里的收益之一：归桶只在这一处做，
        // 于是清单、行情、日线三个域名天然共享同一个计数（和同一份 IP 额度）。
        ClientHttpRequestExecution execution = (req, body) -> mock(ClientHttpResponse.class);
        interceptor.intercept(requestTo("https://datacenter-web.eastmoney.com/api/data/v1/get"),
                new byte[0], execution);
        interceptor.intercept(requestTo("https://push2.eastmoney.com/api/qt/ulist.np/get"),
                new byte[0], execution);
        interceptor.intercept(requestTo("https://push2his.eastmoney.com/api/qt/stock/kline/get"),
                new byte[0], execution);

        assertThat(row("eastmoney")).containsEntry("calls", 3L);
    }

    @Test
    void aRefusedCallIsCountedAsThrottledAndStillThrown() {
        // 服务端一个字节没回就把连接掐了 —— 东财限流的典型症状
        ClientHttpRequestExecution execution = (req, body) -> {
            throw new EOFException("服务端没有回任何东西");
        };

        assertThatThrownBy(() -> interceptor.intercept(
                requestTo("https://push2.eastmoney.com/api/qt/ulist.np/get"), new byte[0], execution))
                .isInstanceOf(EOFException.class);

        assertThat(row("eastmoney")).containsEntry("calls", 1L).containsEntry("throttled", 1L);
        assertThat(row("eastmoney").get("lastThrottledAt")).isNotNull();
    }

    @Test
    void aResetConnectionCountsButAConnectionRefusalDoesNot() {
        // 这两件事的处置完全相反（一个要停手、一个重试无害），分类沿用
        // MarketDataClient.isThrottled 那一处实现，这里只钉住它确实被用上了。
        ClientHttpRequestExecution reset = (req, body) -> {
            throw new SocketException("Connection reset");
        };
        ClientHttpRequestExecution refused = (req, body) -> {
            throw new ConnectException("Connection refused");
        };

        assertThatThrownBy(() -> interceptor.intercept(
                requestTo("https://hq.sinajs.cn/list=sh510300"), new byte[0], reset))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> interceptor.intercept(
                requestTo("https://hq.sinajs.cn/list=sh510300"), new byte[0], refused))
                .isInstanceOf(IOException.class);

        // 两次都发出去了，但只有「被掐断」那次算被拒
        assertThat(row("sina")).containsEntry("calls", 2L).containsEntry("throttled", 1L);
    }

    @Test
    void aCallBlockedByTheGateIsNotCountedAsACall() throws Exception {
        // 闸门挡下的那次**根本没打出去**。算进 calls 会让「外呼次数」虚高，
        // 也会让「被拒 / 外呼」这个比值失真 —— 而这个比值正是调参要看的东西。
        MarketRateLimitGate blocking = new MarketRateLimitGate(5000, 0);
        MarketCallMetrics m = new MarketCallMetrics();
        MarketRateLimitInterceptor blocked = new MarketRateLimitInterceptor(blocking, m);
        AtomicInteger dialled = new AtomicInteger();
        ClientHttpRequestExecution execution = (req, body) -> {
            dialled.incrementAndGet();
            return mock(ClientHttpResponse.class);
        };

        blocked.intercept(requestTo("https://push2.eastmoney.com/api/qt/ulist.np/get"),
                new byte[0], execution);   // 第一次放行
        assertThatThrownBy(() -> blocked.intercept(
                requestTo("https://push2.eastmoney.com/api/qt/ulist.np/get"), new byte[0], execution))
                .isInstanceOf(MarketDataException.class);

        assertThat(dialled.get()).isEqualTo(1);
        assertThat(m.snapshot().get("eastmoney")).containsEntry("calls", 1L);
    }
}