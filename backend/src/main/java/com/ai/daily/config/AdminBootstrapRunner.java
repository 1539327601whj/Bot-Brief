package com.ai.daily.config;

import com.ai.daily.entity.User;
import com.ai.daily.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
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
@Order(0)
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
        String normalizedAdminEmail = adminEmail.toLowerCase();
        User admin = userService.findByEmail(normalizedAdminEmail);
        if (admin == null) {
            log.warn("Admin 用户 {} 不存在，创建之（请确保 ADMIN_PASSWORD 已配置）", normalizedAdminEmail);
            admin = new User();
            admin.setEmail(normalizedAdminEmail);
            admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            admin.setDisplayName("Admin");
            admin.setRole("ADMIN");
            admin.setAccountType(User.ACCOUNT_NORMAL);
            admin.setEnabled(true);
            userService.save(admin);
            log.info("Admin 用户已创建 id={}", admin.getId());
        } else if ("BOOTSTRAP".equals(admin.getPasswordHash())) {
            admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            admin.setRole("ADMIN");
            admin.setAccountType(User.ACCOUNT_NORMAL);
            admin.setEnabled(true);
            userService.updateById(admin);
            log.info("Admin 用户 {} 密码已用 ADMIN_PASSWORD 初始化", normalizedAdminEmail);
        } else if (!User.ACCOUNT_NORMAL.equals(admin.getAccountType())) {
            admin.setAccountType(User.ACCOUNT_NORMAL);
            userService.updateById(admin);
        }

        LambdaUpdateWrapper<User> w = new LambdaUpdateWrapper<>();
        w.ne(User::getEmail, normalizedAdminEmail)
                .isNotNull(User::getInviteCodeUsed)
                .eq(User::getRole, "ADMIN")
                .set(User::getRole, "USER");
        userService.update(w);
    }
}
