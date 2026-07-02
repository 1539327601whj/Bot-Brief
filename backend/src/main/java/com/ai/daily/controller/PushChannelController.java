package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.entity.PushChannel;
import com.ai.daily.entity.Report;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.PushChannelService;
import com.ai.daily.service.ReportService;
import com.ai.daily.service.push.PushDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 推送渠道 CRUD（按当前登录用户隔离）
 */
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class PushChannelController {

    private static final Set<String> ALLOWED_TYPES = Set.of("email", "wechat", "dingtalk", "feishu");

    private final PushChannelService pushChannelService;
    private final PushDispatcher pushDispatcher;
    private final ReportService reportService;

    @GetMapping
    public Result<List<PushChannel>> list() {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) return Result.error(401, "未登录");
        return Result.ok(pushChannelService.listByUser(uid));
    }

    @PostMapping
    public Result<PushChannel> create(@RequestBody PushChannel body) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) return Result.error(401, "未登录");
        String err = validate(body);
        if (err != null) return Result.error(400, err);
        body.setId(null);
        body.setUserId(uid);
        if (body.getEnabled() == null) body.setEnabled(true);
        pushChannelService.save(body);
        return Result.ok("已创建", body);
    }

    @PutMapping("/{id}")
    public Result<PushChannel> update(@PathVariable Long id, @RequestBody PushChannel body) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) return Result.error(401, "未登录");
        PushChannel existing = pushChannelService.getByIdForUser(id, uid);
        if (existing == null) return Result.error(404, "渠道不存在");
        String err = validate(body);
        if (err != null) return Result.error(400, err);
        existing.setChannelType(body.getChannelType());
        existing.setDisplayName(body.getDisplayName());
        existing.setTarget(body.getTarget());
        existing.setSecret(body.getSecret());
        if (body.getEnabled() != null) existing.setEnabled(body.getEnabled());
        pushChannelService.updateById(existing);
        return Result.ok(existing);
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) return Result.error(401, "未登录");
        PushChannel c = pushChannelService.getByIdForUser(id, uid);
        if (c == null) return Result.error(404, "渠道不存在");
        pushChannelService.removeById(id);
        return Result.ok("已删除", null);
    }

    /** 测试推送：用最新一条 report 推给这个渠道 */
    @PostMapping("/{id}/test")
    public Result<String> test(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        if (uid == null) return Result.error(401, "未登录");
        PushChannel c = pushChannelService.getByIdForUser(id, uid);
        if (c == null) return Result.error(404, "渠道不存在");
        Report r = reportService.getLatestReport();
        if (r == null) return Result.error(404, "暂无简报可推送");
        try {
            pushDispatcher.sendOne(c, r);
            return Result.ok("测试推送已发出，请到目标渠道查看", null);
        } catch (Exception e) {
            return Result.error(500, "测试推送失败：" + e.getMessage());
        }
    }

    private String validate(PushChannel b) {
        if (b == null) return "请求体为空";
        if (b.getChannelType() == null || !ALLOWED_TYPES.contains(b.getChannelType()))
            return "渠道类型必须是 email/wechat/dingtalk/feishu";
        if (b.getTarget() == null || b.getTarget().isBlank())
            return "target 不能为空（邮箱地址 或 webhook URL）";
        return null;
    }
}
