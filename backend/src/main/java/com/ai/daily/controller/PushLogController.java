package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.entity.PushLog;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.PushLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/push-logs")
@RequiredArgsConstructor
public class PushLogController {

    private final PushLogService pushLogService;

    @GetMapping
    public Result<List<PushLog>> recent(@RequestParam(defaultValue = "100") int limit) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) return Result.error(401, "未登录");
        return Result.ok(pushLogService.recentByUser(uid, limit));
    }
}
