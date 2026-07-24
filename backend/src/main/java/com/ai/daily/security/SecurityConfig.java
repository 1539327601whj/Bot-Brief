package com.ai.daily.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * JWT 无状态鉴权及集中式 API 授权规则。
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final SecurityErrorHandlers securityErrorHandlers;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/auth/login", "/api/auth/register", "/api/auth/demo").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/reports/ingest", "/api/market-valuations/ingest").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/market-valuations/*/latest", "/api/health").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,
                                "/api/push/wechat", "/api/reports").denyAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/auth/me", "/api/reports", "/api/reports/latest", "/api/stats/dashboard").authenticated()
                        .requestMatchers(new RegexRequestMatcher("^/api/reports/\\d+$", "GET")).authenticated()
                        .requestMatchers("/api/admin/**")
                                .access(new WebExpressionAuthorizationManager("hasRole('ADMIN') and hasAuthority('ACCOUNT_NORMAL')"))
                        .anyRequest().hasAuthority("ACCOUNT_NORMAL")
                )
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(securityErrorHandlers)
                        .accessDeniedHandler(securityErrorHandlers)
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
