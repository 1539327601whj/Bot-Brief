package com.ai.daily.controller;

import com.ai.daily.dto.PushChannelCreateRequest;
import com.ai.daily.dto.PushChannelResponse;
import com.ai.daily.dto.PushChannelUpdateRequest;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.push.PushDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class PushChannelController {

    private final PushChannelService pushChannelService;
    private final PushDispatcher pushDispatcher;
    private final ReportService reportService;

    @GetMapping
    public Result<List<PushChannelResponse>> list() {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(pushChannelService.listResponsesByUser(userId));
        } catch (IllegalStateException e) {
            return Result.error(503, e.getMessage());
        }
    }

    @PostMapping
    public Result<PushChannelResponse> create(@RequestBody PushChannelCreateRequest request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok("已创建", pushChannelService.createForUser(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(503, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<PushChannelResponse> update(@PathVariable Long id, @RequestBody PushChannelUpdateRequest request) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            PushChannelResponse response = pushChannelService.updateForUser(id, userId, request);
            return response == null ? Result.error(404, "渠道不存在") : Result.ok(response);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (IllegalStateException e) {
            return Result.error(503, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return pushChannelService.removeForUser(id, userId)
                ? Result.ok("已删除", null)
                : Result.error(404, "渠道不存在");
    }

    @PostMapping("/{id}/test")
    public Result<String> test(@PathVariable Long id) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            PushChannel channel = pushChannelService.getByIdForUser(id, userId);
            if (channel == null) return Result.error(404, "渠道不存在");
            Report report = reportService.getLatestReport();
            if (report == null) return Result.error(404, "暂无简报可推送");
            pushDispatcher.sendOne(channel, report);
            return Result.ok("测试推送已发出，请到目标渠道查看", null);
        } catch (IllegalStateException e) {
            return Result.error(503, safeMessage(e));
        } catch (Exception e) {
            return Result.error(500, "测试推送失败：" + safeMessage(e));
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "推送服务异常" : message;
    }
}
