package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「低估精选」接口。**页面可见范围**与「市场观察」一致（管理员与公开 Demo），
 * 但**触发筛选只有管理员**——见 {@link #scan}。历史的**读**侧按页面可见范围放行
 * （管理员 + Demo），写侧没有入口：只有成功跑一次筛选才会落一行。
 *
 * <p><b>关于「落库」</b>：筛选本身仍是一次实时计算——不推送、不触发定时任务、
 * 结果不进库当缓存用。唯一的写是**每次成功筛选落一行历史**（{@link ScreenerHistoryService}），
 * 为的是让用户能回看「这次和上次差在哪」。写失败只记 warning 并把这句话挂进返回体的
 * 降级说明里，**不影响本次结果**。
 * {@link StockScreenerService} 自己依旧一行都不写，那边的「不写库」注释仍然成立，别去改它。
 *
 * <p>为什么触发要单独收窄：一次筛选要向东财发二十多次请求（清单 + 批量行情 + 日线），
 * 服务器出口 IP 会被按 IP 限流。Demo 能跑的话，等于多一个可以随时消耗配额的入口。
 * 前端把按钮置灰只是提示，**真正生效的是这里的服务端判定**——Demo 拿着自己的 token
 * 直接 POST 过来，不过这一关就跑不起来。
 */
@Slf4j
@RestController
@RequestMapping("/api/stock-screener")
@RequiredArgsConstructor
public class StockScreenerController {

    private final StockScreenerService stockScreenerService;
    private final ScreenerHistoryService historyService;
    private final MarketCallMetrics marketCallMetrics;
    private final ScreenerCache screenerCache;

    /**
     * 不带任何条件即使用默认条件；body 可以整体缺省。
     *
     * <p>这里返回 HTTP 200 + body.code=403，与 {@code AdminController} 同一套写法，前端读
     * {@code res.data.code}。**不要改到 {@code SecurityConfig} 里用路径规则拦**：那样拿到的是
     * Spring Security 的通用 403，丢掉这里这句能直接展示给用户的中文提示，也少了一条
     * 「谁在什么时候试图越权」的日志。{@code SecurityConfig} 那边保持 {@code authenticated()} 即可。
     * 下面两个读历史的口径同理。
     */
    @PostMapping("/scan")
    public Result<ScreenerResultDTO> scan(@RequestBody(required = false) ScreenerParams params) {
        if (!SecurityUtils.isAdmin()) {
            log.warn("非管理员尝试触发低估精选 user={}", SecurityUtils.currentUserId());
            return Result.error(403, "低估精选仅管理员可运行");
        }
        try {
            return Result.ok(recordHistory(stockScreenerService.scan(params)));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            // 限流用一个单独的状态码：前端要据此**停掉自动刷新**并显示等待时间，
            // 与「网络不通」的处置完全不同（后者可以立刻重试，前者越试越糟）。
            log.warn("低估精选被行情源限流：{}", e.getMessage());
            return Result.error(429, e.getMessage());
        } catch (MarketDataException e) {
            // 行情源不可达必须如实报错，不能返回空列表假装「今天没有候选」
            log.warn("低估精选取数失败", e);
            return Result.error(503, e.getMessage());
        } catch (RuntimeException e) {
            log.error("低估精选执行失败", e);
            return Result.error(500, "筛选执行失败：" + e.getMessage());
        }
    }

    /**
     * 行情外呼与限流的观测量：按源给出「今天打了几次、被打回来几次、最近一次是什么时候、
     * 现在冷却到几点」。**只读，且只给管理员**——它是运维诊断用的，不是页面内容。
     *
     * <p>为什么要有这个端点：限流此前只有一条事件式日志（{@code ScreenerCache} 的
     * 「{} 限流，冷却至 {}」），没有累计。于是「最近老是被打回来」只能靠感觉，
     * 而调限速参数（{@code screener.rate-limit-min-interval-ms}）也只能拍脑袋——
     * 拍错了的代价是首屏变慢或者限流照旧，两种都要几天才看得出来。
     *
     * <p>{@code throttled} 与 {@code cooldownUntil} 回答的是**两个不同的问题**，刻意并列：
     * 前者是「被打回来了几次」这个事实，后者是「现在停手停到几点」这个策略。
     * 有些路径会吞掉限流异常继续跑（{@code MarketDataClient.fetchEtfQuotes} 为了不丢掉
     * 整页结果，捕获后返回空 Map），那些调用不会进冷却，但它们确实被拒了——
     * 只看冷却表会低估真实的出口压力。
     *
     * <p>用 200 + body.code=403 与 {@link #scan} 同一套写法，理由见那个方法上的注释。
     */
    @GetMapping("/rate-limit-stats")
    public Result<Map<String, Object>> rateLimitStats() {
        if (!SecurityUtils.isAdmin()) {
            log.warn("非管理员尝试查看行情外呼统计 user={}", SecurityUtils.currentUserId());
            return Result.error(403, "行情外呼统计仅管理员可查看");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("day", String.valueOf(marketCallMetrics.currentDay()));
        body.put("since", String.valueOf(marketCallMetrics.since()));
        Map<String, Map<String, Object>> providers = marketCallMetrics.snapshot();
        // 冷却从 ScreenerCache 现取，不复制进计数里：那是策略状态，两处各存一份迟早会各说各话
        for (Map.Entry<String, Map<String, Object>> entry : providers.entrySet()) {
            entry.getValue().put("cooldownUntil",
                    screenerCache.cooldownUntil(entry.getKey()).map(String::valueOf).orElse(null));
        }
        body.put("providers", providers);
        return Result.ok(body);
    }

    /**
     * 历史列表。按页面可见范围放行（管理员 + Demo），分页 size 上限与
     * {@code MybatisPlusConfig} 的分页拦截器一致，超了直接 400 而不是被静默截断。
     */
    @GetMapping("/history")
    public Result<Map<String, Object>> history(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        if (!SecurityUtils.canReadPublicDigest()) {
            log.warn("非授权账号尝试读取低估精选历史 user={}", SecurityUtils.currentUserId());
            return Result.error(403, "低估精选仅管理员和 Demo 可见");
        }
        if (page < 1) return Result.error(400, "页码必须从 1 开始");
        if (size < 1 || size > ScreenerHistoryService.MAX_PAGE_SIZE) {
            return Result.error(400, "每页条数必须在 1-" + ScreenerHistoryService.MAX_PAGE_SIZE + " 之间");
        }
        try {
            var p = historyService.page(page, size);
            List<ScreenerHistoryDTO.Item> records = new ArrayList<>();
            for (var row : p.getRecords()) records.add(historyService.toItem(row));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("records", records);
            body.put("total", p.getTotal());
            body.put("pages", p.getPages());
            body.put("current", p.getCurrent());
            body.put("size", p.getSize());
            return Result.ok(body);
        } catch (RuntimeException e) {
            log.error("读取低估精选历史失败", e);
            return Result.error(500, "读取筛选历史失败：" + e.getMessage());
        }
    }

    /** 历史详情：列表项 + 当初那次筛选的完整结果，前端据此整页回放。 */
    @GetMapping("/history/{id}")
    public Result<ScreenerHistoryDTO.Detail> historyDetail(@PathVariable long id) {
        if (!SecurityUtils.canReadPublicDigest()) {
            log.warn("非授权账号尝试读取低估精选历史 user={}", SecurityUtils.currentUserId());
            return Result.error(403, "低估精选仅管理员和 Demo 可见");
        }
        try {
            Optional<ScreenerHistoryDTO.Detail> detail = historyService.detail(id);
            if (detail.isEmpty()) return Result.error(404, "该次筛选历史不存在");
            return Result.ok(detail.get());
        } catch (RuntimeException e) {
            log.error("读取低估精选历史详情失败 id={}", id, e);
            return Result.error(500, "读取该次筛选历史失败：" + e.getMessage());
        }
    }

    /**
     * 落一行历史，并把「没落成」说出来。
     *
     * <p>历史是审计附属品：写失败**绝不能**把一次成功的筛选变成错误页。但也**不能悄悄算了**——
     * 用户下次翻历史找不到这一条时，会以为是自己看漏了或被清理了，而真正的原因
     * （库写不进去）就这么消失了。所以往口径摘要的降级列表里挂一句，跟着结果一起显示。
     */
    private ScreenerResultDTO recordHistory(ScreenerResultDTO result) {
        try {
            var user = SecurityUtils.currentUserOrNull();
            historyService.record(result.appliedParams(), result,
                    SecurityUtils.currentUserId(),
                    user == null ? null : user.getEmail());
            return result;
        } catch (RuntimeException e) {
            log.warn("筛选历史落库失败（本次结果照常返回）：{}", e.toString());
            return result.withExtraDegradation(
                    "本次筛选历史没有存下来（写库失败），所以历史列表里不会有这一条；"
                            + "筛选结果本身不受影响。原因：" + MarketDataClient.shortReason(e));
        }
    }
}