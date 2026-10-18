package com.ai.daily.screener.v2;

import com.ai.daily.dto.Result;
import com.ai.daily.screener.MarketDataException;
import com.ai.daily.screener.v2.ai.BriefV2Service;
import com.ai.daily.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Supplier;

/**
 * 代码查询 v2 接口。
 *
 * <p>与 v1（{@code /api/code-lookup}）并行存在：v2 是本轮重做的结论仪表盘，
 * v1 保留一个版本周期作为回退绳。两者共用同一套权限判定和异常语义，
 * 唯一多出来的一条是「数据底座未就绪 → 503」，因为那是部署问题，
 * 用 500 会让人一直点重试。
 *
 * <p>端点拆分遵循「先快后慢」：{@code core} 在预算内给出首屏，
 * {@code detail} 分段加载财务/资金/事件，{@code series} 只读本地库，
 * {@code status} 供前端轮询后台同步。
 */
@Slf4j
@RestController
@RequestMapping("/api/code-lookup/v2")
@RequiredArgsConstructor
public class CodeLookupV2Controller {

    private static final String DENIED = "代码查询仅管理员和 Demo 可见";

    private final CodeLookupV2Service service;
    private final SeriesService seriesService;
    private final BriefV2Service briefService;
    private final CodeLookupMetrics metrics;

    @GetMapping("/core")
    public Result<CodeLookupV2DTO.CoreView> core(
            @RequestParam(required = false) String code) {
        return run("首屏", code, () -> service.core(code));
    }

    @GetMapping("/detail")
    public Result<CodeLookupV2DTO.DetailView> detail(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String snapshotId) {
        return run("明细", code, () -> service.detail(code, snapshotId));
    }

    @GetMapping("/series")
    public Result<CodeLookupV2DTO.SeriesView> series(
            @RequestParam(required = false) String code,
            @RequestParam(required = false, defaultValue = "price") String kind,
            @RequestParam(required = false, defaultValue = "1y") String range,
            @RequestParam(required = false) String interval) {
        return run("图表序列", code, () -> seriesService.series(code, kind, range, interval));
    }

    @GetMapping("/status")
    public Result<CodeLookupV2DTO.StatusView> status(
            @RequestParam(required = false) String code) {
        return run("同步状态", code, () -> service.status(code));
    }

    @GetMapping("/search")
    public Result<List<CodeLookupV2DTO.SearchHit>> search(
            @RequestParam(required = false) String q) {
        return run("代码搜索", q, () -> service.search(q));
    }

    /**
     * 进程内运行指标：首屏耗时 P95、缓存命中率、限流与失败次数。
     *
     * <p>只给管理员看。它是**聚合值**，不含用户和代码，与按需查询的定位一致；
     * 重启清零，长期趋势仍需日志平台。
     */
    @GetMapping("/metrics")
    public Result<CodeLookupMetrics.MetricsView> metrics() {
        if (!SecurityUtils.isAdmin()) {
            return Result.error(403, "运行指标仅管理员可见");
        }
        return Result.ok(metrics.snapshot());
    }

    /**
     * 手动生成解读。慢、可能计费，所以单独一个端点。
     * 未配 key / 超时 / 校验不过都返回 200，由 {@code available/fallback/reason} 区分。
     */
    @GetMapping("/brief")
    public Result<CodeLookupV2DTO.BriefView> brief(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String snapshotId) {
        return run("解读", code, () -> briefService.brief(code, snapshotId));
    }

    /**
     * 统一权限与异常映射。
     *
     * <p>顺序不能换：
     * <ol>
     *   <li>格式非法 → 400（换写法就能解决）；</li>
     *   <li>查不到 → 404（**与取数失败分开**，否则用户会一直重试一个不存在的代码）；</li>
     *   <li>限流 → 429，前端据此停掉自动重试；</li>
     *   <li>底座未就绪 / 源不可达 → 503；</li>
     *   <li>其余 → 500。</li>
     * </ol>
     */
    private <T> Result<T> run(String action, String input, Supplier<T> body) {
        if (!SecurityUtils.canReadPublicDigest()) {
            log.warn("非授权账号尝试代码查询 v2 {} user={} input={}",
                    action, SecurityUtils.currentUserId(), input);
            return Result.error(403, DENIED);
        }
        long start = System.nanoTime();
        boolean isCore = "首屏".equals(action);
        if (isCore) {
            metrics.recordCoreRequest();
        }
        try {
            T value = body.get();
            if (value instanceof CodeLookupV2DTO.CoreView core) {
                metrics.recordCore(System.nanoTime() - start, core.fromCache(), core.syncPending());
                // 降级成功（本地数据 + 冷却提示）也算遇到限流：只统计 HTTP 429 会漏掉
                // 「源已经拒绝、只是页面还能用」的那一类。
                if (core.retryAfterMs() > 0) {
                    metrics.recordRateLimited();
                }
            }
            return Result.ok(value);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (InstrumentNotFoundException e) {
            return Result.error(404, e.getMessage());
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            metrics.recordRateLimited();
            log.warn("代码查询 v2 {} 被行情源限流：{}", action, e.getMessage());
            return Result.error(429, e.getMessage());
        } catch (WarehouseUnavailableException | MarketDataException e) {
            metrics.recordFailure();
            log.warn("代码查询 v2 {} 不可用 input={}：{}", action, input, e.getMessage());
            return Result.error(503, e.getMessage());
        } catch (RuntimeException e) {
            metrics.recordFailure();
            log.error("代码查询 v2 {} 执行失败 input={}", action, input, e);
            return Result.error(500, "查询失败：" + e.getMessage());
        }
    }
}
