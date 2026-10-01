package com.ai.daily.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册 traceId 与 access log 两个过滤器。
 *
 * 用 FilterRegistrationBean 而不是给过滤器标 @Component，是因为顺序：
 * Spring Security 的 FilterChainProxy 默认顺序是 -100，标 @Component 的过滤器会被
 * 注册到 LOWEST_PRECEDENCE（更靠后），401/403 就落在 Security 里、拿不到 traceId。
 * 放 HIGHEST_PRECEDENCE 能稳定排在 Security 之前。
 * 另外，同时标 @Component 又放进 FilterRegistrationBean 会被注册两次、重复执行。
 */
@Configuration
public class WebLoggingConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration() {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter());
        registration.addUrlPatterns("/*");
        // 必须排在 traceId 之后，才能从 MDC 读到 traceId 与 userId
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("accessLogFilter");
        return registration;
    }
}