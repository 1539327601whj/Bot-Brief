package com.ai.daily.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class JwtService {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration-hours:24}")
    private long expirationHours;

    @PostConstruct
    void validateSecret() {
        key();
    }

    private SecretKey key() {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            log.error("JWT 密钥长度不足 secret_bytes={} 至少需要 32 字节", bytes.length);
            throw new IllegalStateException("JWT_SECRET 长度不足 32 字节，无法签发登录 token");
        }
        return Keys.hmacShaKeyFor(bytes);
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
        log.debug("签发 JWT user={} 有效期分钟={}", userId, validity.toMinutes());
        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(new Date(now))
                .expiration(new Date(now + validity.toMillis()))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        log.debug("JWT 解析成功 uid={}", claims.get("uid"));
        return claims;
    }

    public Long userId(Claims claims) {
        if (claims == null) {
            log.debug("JWT 解析用户失败 reason=claims为空");
            return null;
        }
        Object uid = claims.get("uid");
        if (uid instanceof Number number) {
            log.debug("JWT 解析用户成功 uid={} source=uid声明", number);
            return number.longValue();
        }
        if (uid != null) {
            try {
                long parsed = Long.parseLong(uid.toString());
                log.debug("JWT 解析用户成功 uid={} source=uid声明", parsed);
                return parsed;
            } catch (NumberFormatException ignored) {
                log.debug("JWT 解析用户失败 reason=uid声明不是数字 uid={}", uid);
                // fall through to subject
            }
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            log.debug("JWT 解析用户失败 reason=缺少uid声明且缺少subject");
            return null;
        }
        try {
            long parsed = Long.parseLong(subject);
            log.debug("JWT 解析用户成功 uid={} source=subject", parsed);
            return parsed;
        } catch (NumberFormatException ignored) {
            log.debug("JWT 解析用户失败 reason=subject不是数字 subject={}", subject);
            return null;
        }
    }
}
