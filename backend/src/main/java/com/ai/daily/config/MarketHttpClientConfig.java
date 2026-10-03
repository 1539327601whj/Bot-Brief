package com.ai.daily.config;

import com.ai.daily.screener.MarketRateLimitInterceptor;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * 「低估精选」外呼东财用的 RestTemplate。
 *
 * <p>与 {@link PushHttpClientConfig} 同一套超时纪律：连接 5s / 读 10s。
 * 行情接口偶尔会长时间不返回，**没有超时上限就会把用户点的那一下挂在那里**。
 *
 * <p>生产的那个 bean 上还挂着 {@link MarketRateLimitInterceptor}（事前限速 + 按源计数）。
 * 它挂在**模板**这一层而不是某一个客户端里，因为四个行情客户端注入的是同一个 bean，
 * 而 {@code AltQuoteSource} 绕过了 {@code MarketDataClient.fetchJson} 自己实现传输——
 * 挂在这里才一处覆盖全部源。
 *
 * <p><b>测试要用下面那个不带拦截器的重载</b>（{@link #marketRestTemplate(Duration, Duration)}）：
 * 它给出与生产**逐字段一致**的超时与连接纪律，但没有闸门。{@code MockRestServiceServer}
 * 只替换底层的 request factory，拦截器照跑——带着闸门建实例，测试就会真的按间隔睡过去。
 */
@Slf4j
@Configuration
public class MarketHttpClientConfig {

    @Bean("marketRestTemplate")
    public RestTemplate marketRestTemplate(
            @Value("${screener.http.connect-timeout:5s}") Duration connectTimeout,
            @Value("${screener.http.read-timeout:10s}") Duration readTimeout,
            MarketRateLimitInterceptor rateLimitInterceptor) {
        RestTemplate restTemplate = marketRestTemplate(connectTimeout, readTimeout);
        restTemplate.setInterceptors(List.of(rateLimitInterceptor));
        log.debug("行情 HTTP 客户端已挂上限速与计数拦截器");
        return restTemplate;
    }

    /**
     * 不带限速闸门的工厂。**测试专用**（生产请用上面那个 {@code @Bean}）：
     * 单元测试不该被生产限速参数影响，也不该因为真睡 150ms 而变慢。
     */
    public RestTemplate marketRestTemplate(
            Duration connectTimeout,
            Duration readTimeout) {
        log.debug("行情 HTTP 客户端超时配置 connect={} read={}", connectTimeout, readTimeout);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                .setRedirectsEnabled(false)
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .disableRedirectHandling()
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
    }
}