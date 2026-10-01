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

@Slf4j
@Configuration
public class PushHttpClientConfig {

    @Bean("pushRestTemplate")
    public RestTemplate pushRestTemplate(
            @Value("${push-channel.http.connect-timeout:5s}") java.time.Duration connectTimeout,
            @Value("${push-channel.http.read-timeout:10s}") java.time.Duration readTimeout) {
        log.debug("推送 HTTP 客户端超时配置 connect={} read={}", connectTimeout, readTimeout);
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
