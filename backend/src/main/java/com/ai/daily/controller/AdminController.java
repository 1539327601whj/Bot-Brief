package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.entity.InviteCode;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.InviteCodeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理员接口。阶段 1 仅暴露邀请码管理。
 * 手动在方法内检查 isAdmin() —— 因为 SecurityConfig 阶段 1 是 permitAll。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final InviteCodeService inviteCodeService;

    @PostMapping("/invite-codes")
    public Result<List<InviteCode>> generate(@RequestParam(defaultValue = "1") int count) {
        Long adminId = SecurityUtils.currentUserId();
        if (adminId == null || !SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可操作");
        return Result.ok(inviteCodeService.generate(adminId, count));
    }

    @GetMapping("/invite-codes")
    public Result<List<InviteCode>> list() {
        if (!SecurityUtils.isAdmin()) return Result.error(403, "仅管理员可操作");
        LambdaQueryWrapper<InviteCode> w = new LambdaQueryWrapper<>();
        w.orderByDesc(InviteCode::getCreatedAt);
        return Result.ok(inviteCodeService.list(w));
    }
}
