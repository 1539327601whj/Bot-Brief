package com.ai.daily.controller;

import com.ai.daily.dto.ContentGrowthDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.CompetitorAccount;
import com.ai.daily.entity.ContentAccount;
import com.ai.daily.entity.ContentWork;
import com.ai.daily.security.SecurityUtils;
import com.ai.daily.service.ContentGrowthService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/content-growth")
@RequiredArgsConstructor
public class ContentGrowthController {

    private final ContentGrowthService contentGrowthService;

    @GetMapping("/accounts")
    public Result<List<ContentAccount>> listAccounts() {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(contentGrowthService.listAccounts(userId));
    }

    @PostMapping("/accounts")
    public Result<ContentAccount> createAccount(@RequestBody ContentGrowthDTO.AccountRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        String error = validateAccount(request);
        if (error != null) return Result.error(400, error);
        return Result.ok(contentGrowthService.createAccount(userId, request));
    }

    @PutMapping("/accounts/{id}")
    public Result<ContentAccount> updateAccount(@PathVariable Long id, @RequestBody ContentGrowthDTO.AccountRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        String error = validateAccount(request);
        if (error != null) return Result.error(400, error);
        try {
            return Result.ok(contentGrowthService.updateAccount(userId, id, request));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @DeleteMapping("/accounts/{id}")
    public Result<String> deleteAccount(@PathVariable Long id) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            contentGrowthService.deleteAccount(userId, id);
            return Result.ok("账号已删除", null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/works")
    public Result<Page<ContentGrowthDTO.WorkItem>> listWorks(
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(contentGrowthService.listWorks(userId, accountId, Math.max(1, page), Math.max(1, size)));
    }

    @PostMapping("/works")
    public Result<ContentWork> createWork(@RequestBody ContentGrowthDTO.WorkRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        String error = validateWork(request);
        if (error != null) return Result.error(400, error);
        try {
            return Result.ok(contentGrowthService.createWork(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PutMapping("/works/{id}")
    public Result<ContentWork> updateWork(@PathVariable Long id, @RequestBody ContentGrowthDTO.WorkRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        String error = validateWork(request);
        if (error != null) return Result.error(400, error);
        try {
            return Result.ok(contentGrowthService.updateWork(userId, id, request));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @DeleteMapping("/works/{id}")
    public Result<String> deleteWork(@PathVariable Long id) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            contentGrowthService.deleteWork(userId, id);
            return Result.ok("作品已删除", null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/overview")
    public Result<ContentGrowthDTO.Overview> overview(@RequestParam(required = false) Long accountId) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(contentGrowthService.getOverview(userId, accountId));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping("/ai/hot-analysis")
    public Result<ContentGrowthDTO.AiTextResponse> hotAnalysis(@RequestBody(required = false) ContentGrowthDTO.HotAnalysisRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(contentGrowthService.analyzeHotWork(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @PostMapping("/ai/topic-recommendations")
    public Result<ContentGrowthDTO.AiTextResponse> topicRecommendations(@RequestBody(required = false) ContentGrowthDTO.TopicRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(contentGrowthService.recommendTopics(userId, request));
    }

    @PostMapping("/ai/rewrite-advice")
    public Result<ContentGrowthDTO.AiTextResponse> rewriteAdvice(@RequestBody ContentGrowthDTO.RewriteRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            return Result.ok(contentGrowthService.rewriteAdvice(userId, request));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    @GetMapping("/competitors")
    public Result<List<CompetitorAccount>> listCompetitors() {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        return Result.ok(contentGrowthService.listCompetitors(userId));
    }

    @PostMapping("/competitors")
    public Result<CompetitorAccount> createCompetitor(@RequestBody ContentGrowthDTO.CompetitorRequest request) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        if (request == null || blank(request.getPlatform()) || blank(request.getAccountName())) {
            return Result.error(400, "平台和账号名不能为空");
        }
        return Result.ok(contentGrowthService.createCompetitor(userId, request));
    }

    @DeleteMapping("/competitors/{id}")
    public Result<String> deleteCompetitor(@PathVariable Long id) {
        Long userId = currentUserId();
        if (userId == null) return Result.error(401, "未登录");
        try {
            contentGrowthService.deleteCompetitor(userId, id);
            return Result.ok("竞品账号已删除", null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }

    private Long currentUserId() {
        return SecurityUtils.currentUserId();
    }

    private String validateAccount(ContentGrowthDTO.AccountRequest request) {
        if (request == null) return "请求不能为空";
        if (blank(request.getPlatform())) return "平台不能为空";
        if (blank(request.getAccountName())) return "账号名不能为空";
        return null;
    }

    private String validateWork(ContentGrowthDTO.WorkRequest request) {
        if (request == null) return "请求不能为空";
        if (request.getAccountId() == null) return "账号不能为空";
        if (blank(request.getPlatform())) return "平台不能为空";
        if (blank(request.getTitle())) return "作品标题不能为空";
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
