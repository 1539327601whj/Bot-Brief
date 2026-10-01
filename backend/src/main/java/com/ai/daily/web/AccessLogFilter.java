package com.ai.daily.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 每个请求记一条 access 日志：method / path / 状态码 / 耗时 / 用户。
 * traceId 由 TraceIdFilter 放进 MDC，日志 pattern 里已经带，消息体不再重复。
 *
 * 注意：本类刻意不标 @Component —— 由 WebLoggingConfig 用 FilterRegistrationBean 注册，
 * 保证排在 Spring Security 之前，401/403 也能记上。
 */
@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    /**
     * 只在成功时（2xx/3xx）不记的路径：
     * 预检请求由 CorsUtils 另行判断；其余是机器高频调用（poller 轮询、心跳）与错误转发页，
     * 逐个记会把日志刷满，它们的业务结果在各自 controller 里已有日志。
     * 注意 4xx/5xx **仍然会记** —— 高频接口一旦出错就是异常信号，静默掉反而难查。
     */
    private static final Set<String> SKIP_PATHS = Set.of(
            "/api/health",
            "/error",
            "/api/reports/poller-heartbeat",
            "/api/reports/due-generations",
            "/api/reports/generation-status",
            "/api/reports/dispatch-due",
            "/api/reports/record-delivery",
            "/api/reports/subscribed-topics");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 跨域预检没有业务含义，记了只会让每个前端请求多出一条
        if (CorsUtils.isPreFlightRequest(request)) {
            chain.doFilter(request, response);
            return;
        }

        long startNanos = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable e) {
            // 自己绝不往外抛新异常，只记下来再原样抛出，避免把请求变成 500
            failure = e;
            throw e;
        } finally {
            logAccess(request, response, startNanos, failure);
        }
    }

    private void logAccess(HttpServletRequest request, HttpServletResponse response, long startNanos, Throwable failure) {
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        int status = response.getStatus();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String userId = MDC.get(TraceIdFilter.MDC_USER_ID);
        String user = userId != null ? userId : "-";

        try {
            if (failure != null) {
                log.error("access method={} path={} status={} cost={}ms user={}",
                        method, path, status, costMs, user, failure);
            } else if (status >= 500) {
                log.error("access method={} path={} status={} cost={}ms user={}", method, path, status, costMs, user);
            } else if (status >= 400) {
                log.warn("access method={} path={} status={} cost={}ms user={}", method, path, status, costMs, user);
            } else if (!SKIP_PATHS.contains(path)) {
                log.info("access method={} path={} status={} cost={}ms user={}", method, path, status, costMs, user);
            }
        } catch (Exception e) {
            // 记日志本身失败不能影响请求
            log.warn("access 日志写入失败 path={}", path, e);
        }
    }
}