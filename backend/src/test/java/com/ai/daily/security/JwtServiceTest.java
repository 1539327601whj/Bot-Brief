package com.ai.daily.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-secret-that-is-at-least-32-bytes-long-for-jwt");
        ReflectionTestUtils.setField(jwtService, "expirationHours", 24L);
    }

    @Test
    void generatesTokenWithCustomValidity() {
        String token = jwtService.generate(7L, "demo@example.com", "USER", Duration.ofMinutes(15));

        Claims claims = jwtService.parse(token);
        long validityMillis = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();

        assertThat(claims.get("uid", Long.class)).isEqualTo(7L);
        assertThat(validityMillis).isEqualTo(Duration.ofMinutes(15).toMillis());
    }

    @Test
    void defaultTokenValidityRemainsConfiguredHours() {
        Claims claims = jwtService.parse(jwtService.generate(8L, "user@example.com", "USER"));

        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(Duration.ofHours(24).toMillis());
    }
}
