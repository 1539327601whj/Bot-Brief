package com.ai.daily.service.impl;

import com.ai.daily.entity.InviteCode;
import com.ai.daily.entity.User;
import com.ai.daily.mapper.UserMapper;
import com.ai.daily.service.InviteCodeService;
import com.ai.daily.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final InviteCodeService inviteCodeService;

    @Override
    public User findByEmail(String email) {
        if (email == null) return null;
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<>();
        w.eq(User::getEmail, email.toLowerCase()).last("LIMIT 1");
        return this.getOne(w);
    }

    @Override
    @Transactional
    public User register(String email, String rawPassword, String displayName, String inviteCode) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("邮箱不能为空");
        if (rawPassword == null || rawPassword.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
        if (inviteCode == null || inviteCode.isBlank()) throw new IllegalArgumentException("必须填写邀请码");

        String normalizedEmail = email.trim().toLowerCase();
        if (findByEmail(normalizedEmail) != null) {
            log.warn("注册失败：邮箱已被注册 email={}", normalizedEmail);
            throw new IllegalArgumentException("邮箱已被注册");
        }

        InviteCode ic = inviteCodeService.findByCode(inviteCode.trim());
        if (ic == null) throw new IllegalArgumentException("邀请码无效");
        if (ic.getUsedBy() != null) throw new IllegalArgumentException("邀请码已被使用");
        if (ic.getExpiresAt() != null && ic.getExpiresAt().isBefore(LocalDateTime.now()))
            throw new IllegalArgumentException("邀请码已过期");

        User u = new User();
        u.setEmail(normalizedEmail);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : normalizedEmail);
        u.setRole("USER");
        u.setAccountType(User.ACCOUNT_NORMAL);
        u.setEnabled(true);
        u.setInviteCodeUsed(ic.getCode());
        this.save(u);

        inviteCodeService.markUsed(ic.getCode(), u.getId());
        log.info("用户注册成功 user={} email={} invite_id={}", u.getId(), normalizedEmail, ic.getId());
        return u;
    }

    @Override
    public User authenticate(String email, String rawPassword) {
        if (email == null || rawPassword == null) return null;
        User u = findByEmail(email.trim().toLowerCase());
        if (u == null || !Boolean.TRUE.equals(u.getEnabled())) {
            if (u == null) {
                log.warn("认证失败 reason=账号不存在 email={}", email);
            } else {
                log.warn("认证失败 reason=账号已停用 email={}", email);
            }
            return null;
        }
        if (User.ACCOUNT_DEMO.equals(u.getAccountType())) {
            log.warn("认证失败 reason=Demo账号不可登录 email={}", email);
            return null;
        }
        if (!passwordEncoder.matches(rawPassword, u.getPasswordHash())) {
            log.warn("认证失败 reason=密码错误 email={}", email);
            return null;
        }
        return u;
    }
}
