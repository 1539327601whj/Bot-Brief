package com.ai.daily.security;

import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.web.TraceIdFilter;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path != null && (path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/auth/demo"))) {
            chain.doFilter(request, response);
            return;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Long userId = jwtService.userId(claims);
                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    User user = userMapper.selectById(userId);
                    if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
                        UserPrincipal principal = new UserPrincipal(
                                user.getId(), user.getEmail(), user.getRole(), user.getAccountType(),
                                user.getPasswordHash(), true);
                        UsernamePasswordAuthenticationToken token2 =
                                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                        token2.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(token2);
                        // 写进 MDC，access log 才能显示是哪个用户；由 TraceIdFilter 统一清理
                        MDC.put(TraceIdFilter.MDC_USER_ID, String.valueOf(user.getId()));
                        log.debug("鉴权通过 user={} path={}", user.getId(), path);
                    } else if (user == null) {
                        log.debug("token 有效但用户不存在，按未登录处理 user={} path={}", userId, path);
                    } else {
                        log.debug("账号已停用，按未登录处理 user={} path={}", userId, path);
                    }
                }
            } catch (Exception e) {
                // token 过期/伪造都会走到这里，属正常情况，用 debug 但保留堆栈便于排查
                log.debug("JWT 解析失败 path={}", path, e);
            }
        }
        chain.doFilter(request, response);
    }
}
