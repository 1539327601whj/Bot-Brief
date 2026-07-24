package com.ai.daily.config;

import com.ai.daily.entity.User;
import com.ai.daily.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoAccountBootstrapRunner implements ApplicationRunner {

    public static final String NON_LOGIN_PASSWORD_SENTINEL = "DEMO_LOGIN_DISABLED";

    private final UserService userService;
    private final DemoAccountProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        String email = normalizeEmail(properties.getEmail());
        validateTokenExpiration(properties.getTokenExpirationMinutes());

        User demo = userService.findByEmail(email);
        if (demo != null && !User.ACCOUNT_DEMO.equals(demo.getAccountType())) {
            throw new IllegalStateException("Demo 邮箱已被普通账号占用: " + email);
        }

        if (demo == null) {
            demo = new User();
            demo.setEmail(email);
            demo.setPasswordHash(NON_LOGIN_PASSWORD_SENTINEL);
            demo.setDisplayName(properties.getDisplayName());
            demo.setRole("USER");
            demo.setAccountType(User.ACCOUNT_DEMO);
            demo.setEnabled(true);
            userService.save(demo);
            log.info("Demo 用户已创建 id={}", demo.getId());
            return;
        }

        if (!NON_LOGIN_PASSWORD_SENTINEL.equals(demo.getPasswordHash())
                || !Objects.equals(properties.getDisplayName(), demo.getDisplayName())
                || !"USER".equals(demo.getRole())
                || !Boolean.TRUE.equals(demo.getEnabled())) {
            demo.setPasswordHash(NON_LOGIN_PASSWORD_SENTINEL);
            demo.setDisplayName(properties.getDisplayName());
            demo.setRole("USER");
            demo.setEnabled(true);
            userService.updateById(demo);
            log.info("Demo 用户配置已同步 id={}", demo.getId());
        }
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException("demo.email 不能为空");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validateTokenExpiration(int minutes) {
        if (minutes < 5 || minutes > 60) {
            throw new IllegalStateException("demo.token-expiration-minutes 必须在 5 到 60 之间");
        }
    }
}
