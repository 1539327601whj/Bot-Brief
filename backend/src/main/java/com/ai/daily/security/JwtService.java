package com.ai.daily.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-hours:24}")
    private long expirationHours;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(Long userId, String email, String role) {
        return generate(userId, email, role, Duration.ofHours(expirationHours));
    }

    public String generate(Long userId, String email, String role, Duration validity) {
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("JWT 有效期必须大于 0");
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", userId);
        claims.put("email", email);
        claims.put("role", role);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + validity.toMillis()))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
