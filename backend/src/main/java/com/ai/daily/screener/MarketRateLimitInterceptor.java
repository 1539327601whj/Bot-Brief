package com.ai.daily.screener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 挂在 {@code marketRestTemplate} 上的出站闸门：**发车前限速，打出去后计数**。
 *
 * <h2>为什么在这一层</h2>
 *
 * <p>这是唯一能**一处覆盖全部源**的位置。四个行情客户端（{@link MarketDataClient}、
 * {@link AltQuoteSource}、{@link OffPoolIndexClient}、{@link StockValuationClient}）
 * 注入的都是同一个 {@code marketRestTemplate} bean，而 {@code MarketDataClient.fetchJson}
 * 这条公共路径**覆盖不到腾讯和新浪**——{@link AltQuoteSource} 为了强制 GBK 自己实现了传输。
 * 只插 {@code fetchJson} 就得同时改两处，将来新接入的源还会再漏一次；
 * 挂在模板上则连"以后新写的客户端"都自动被覆盖。
 *
 * <h2>为什么和 {@link MarketDataClient#isThrottled} 同包</h2>
 *
 * <p>那个判据是包级可见的，且 {@code MarketDataClient} 的注释要求「限流的翻译必须只有一处实现，
 * 否则『哪个客户端会进冷却』就说不清了」。把拦截器放在 {@code config} 包里会逼出一个
 * public 版本或者第二份分类器，两条路都在拆那个约束。同包即解，一行可见性都不用改。
 *
 * <p>另外，这一层拿到的正是**底层 IOException**（{@code NoHttpResponseException}、
 * 被重置的 {@code SocketException}、{@code EOFException}）——{@code RestTemplate} 把它们
 * 包成 {@code ResourceAccessException} 是在拦截器**之后**的事，所以判据在这里是原样可用的。
 */
@Slf4j
@Component
public class MarketRateLimitInterceptor implements ClientHttpRequestInterceptor {

    private final MarketRateLimitGate gate;
    private final MarketCallMetrics metrics;

    public MarketRateLimitInterceptor(MarketRateLimitGate gate, MarketCallMetrics metrics) {
        this.gate = gate;
        this.metrics = metrics;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String provider = MarketProviders.of(request.getURI());

        // 先排队再计数：被闸门挡下的那次**根本没打出去**，算进「外呼次数」会让计数虚高，
        // 也会让「被拒/外呼」这个比值失真。
        gate.acquire(provider);
        metrics.recordCall(provider);

        try {
            return execution.execute(request, body);
        } catch (IOException e) {
            // 计数在**抛出的瞬间**做，早于任何调用方的 catch：
            // fetchEtfQuotes 那类「吞掉异常、返回空 Map 继续跑」的路径也会被如实记上。
            if (MarketDataClient.isThrottled(e)) {
                metrics.recordThrottled(provider);
            }
            throw e;
        }
    }
}