package com.ai.daily.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求分配一个 traceId 放进 MDC，logback 的 pattern 用 %X{traceId} 带出来，
 * 于是一个请求散落在各处的日志能按 traceId 串成一条线。
 * 优先复用上游传进来的 X-Request-Id / X-Trace-Id（例如 poller 调后端），没有才自己生成。
 *
 * 注意：本类刻意不标 @Component —— 由 WebLoggingConfig 用 FilterRegistrationBean 注册，
 * 才能拿到 HIGHEST_PRECEDENCE 排在 Spring Security 之前，保证 401/403 也带 traceId。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** 日志 MDC 的 key，logback pattern 里以 %X{traceId} 引用 */
    public static final String MDC_TRACE_ID = "traceId";

    /** 鉴权成功后由 JwtAuthFilter 写入，供 access log 显示 user= */
    public static final String MDC_USER_ID = "userId";

    private static final String HEADER_TRACE_ID = "X-Trace-Id";
    private static final String HEADER_REQUEST_ID = "X-Request-Id";

    /** 上游 header 的值会原样进日志，限制长度并只保留安全字符，避免伪造日志行 */
    private static final int MAX_TRACE_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 线程会被 Tomcat 复用，必须清理干净，否则 traceId 和 userId 会串到下一个请求
            MDC.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String upstream = firstNonBlank(request.getHeader(HEADER_REQUEST_ID), request.getHeader(HEADER_TRACE_ID));
        return upstream != null ? sanitize(upstream) : newTraceId();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second != null && !second.isBlank() ? second : null;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String sanitize(String raw) {
        StringBuilder builder = new StringBuilder(Math.min(raw.length(), MAX_TRACE_ID_LENGTH));
        for (int i = 0; i < raw.length() && builder.length() < MAX_TRACE_ID_LENGTH; i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') {
                builder.append(ch);
            }
        }
        return builder.isEmpty() ? newTraceId() : builder.toString();
    }
}