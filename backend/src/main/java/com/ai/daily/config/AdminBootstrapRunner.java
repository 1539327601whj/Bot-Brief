package com.ai.daily.config;

import com.ai.daily.entity.User;
import com.ai.daily.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时确保 admin 账号存在且密码已用 ADMIN_PASSWORD 初始化。
 * - 若 users 表里没有 admin，创建之。
 * - 若 password_hash == 'BOOTSTRAP'（V2 SQL 里的占位符），用 env 的密码初始化。
 * - 若 admin 已有真实密码，不覆盖（避免重启把密码重置）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.bootstrap-password}")
    private String bootstrapPassword;

    @Override
    public void run(ApplicationArguments args) {
        User admin = userService.findByEmail(adminEmail);
        if (admin == null) {
            log.warn("Admin 用户 {} 不存在，创建之（请确保 ADMIN_PASSWORD 已配置）", adminEmail);
            admin = new User();
            admin.setEmail(adminEmail.toLowerCase());
            admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            admin.setDisplayName("Admin");
            admin.setRole("ADMIN");
            admin.setEnabled(true);
            userService.save(admin);
            log.info("Admin 用户已创建 id={}", admin.getId());
            return;
        }
        if ("BOOTSTRAP".equals(admin.getPasswordHash())) {
            admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            admin.setRole("ADMIN");
            admin.setEnabled(true);
            userService.updateById(admin);
            log.info("Admin 用户 {} 密码已用 ADMIN_PASSWORD 初始化", adminEmail);
        }
    }
}
