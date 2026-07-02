package com.ai.daily.service.impl;

import com.ai.daily.entity.InviteCode;
import com.ai.daily.mapper.InviteCodeMapper;
import com.ai.daily.service.InviteCodeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class InviteCodeServiceImpl extends ServiceImpl<InviteCodeMapper, InviteCode> implements InviteCodeService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉容易混淆字符

    @Override
    public List<InviteCode> generate(Long adminUserId, int count) {
        if (count <= 0 || count > 100) throw new IllegalArgumentException("count 必须在 1-100");
        List<InviteCode> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            InviteCode ic = new InviteCode();
            ic.setCode(randomCode(10));
            ic.setCreatedBy(adminUserId);
            this.save(ic);
            out.add(ic);
        }
        return out;
    }

    @Override
    public InviteCode findByCode(String code) {
        if (code == null) return null;
        LambdaQueryWrapper<InviteCode> w = new LambdaQueryWrapper<>();
        w.eq(InviteCode::getCode, code.trim()).last("LIMIT 1");
        return this.getOne(w);
    }

    @Override
    public void markUsed(String code, Long userId) {
        LambdaUpdateWrapper<InviteCode> u = new LambdaUpdateWrapper<>();
        u.eq(InviteCode::getCode, code)
                .isNull(InviteCode::getUsedBy) // 幂等：只有未使用的才 update
                .set(InviteCode::getUsedBy, userId)
                .set(InviteCode::getUsedAt, LocalDateTime.now());
        this.update(u);
    }

    private String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
