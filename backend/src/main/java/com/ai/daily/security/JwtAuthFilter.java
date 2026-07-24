package com.ai.daily.security;

import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Long userId = claims.get("uid", Long.class);
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
                    }
                }
            } catch (Exception e) {
                log.debug("JWT 解析失败：{}", e.getMessage());
            }
        }
        chain.doFilter(request, response);
    }
}
