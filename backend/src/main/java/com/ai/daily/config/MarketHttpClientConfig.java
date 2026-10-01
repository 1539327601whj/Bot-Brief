package com.ai.daily.config;

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

/**
 * 「低估精选」外呼东财用的 RestTemplate。
 *
 * <p>与 {@link PushHttpClientConfig} 同一套超时纪律：连接 5s / 读 10s。
 * 行情接口偶尔会长时间不返回，**没有超时上限就会把用户点的那一下挂在那里**。
 */
@Slf4j
@Configuration
public class MarketHttpClientConfig {

    @Bean("marketRestTemplate")
    public RestTemplate marketRestTemplate(
            @Value("${screener.http.connect-timeout:5s}") java.time.Duration connectTimeout,
            @Value("${screener.http.read-timeout:10s}") java.time.Duration readTimeout) {
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