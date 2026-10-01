package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「低估精选」接口。**页面可见范围**与「市场观察」一致（管理员与公开 Demo），
 * 但**触发筛选只有管理员**——见 {@link #scan}。
 *
 * <p>只读接口：不落库、不推送、不触发任何定时任务，每次调用就是一次实时计算。
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

    /**
     * 不带任何条件即使用默认条件；body 可以整体缺省。
     *
     * <p>这里返回 HTTP 200 + body.code=403，与 {@code AdminController} 同一套写法，前端读
     * {@code res.data.code}。**不要改到 {@code SecurityConfig} 里用路径规则拦**：那样拿到的是
     * Spring Security 的通用 403，丢掉这里这句能直接展示给用户的中文提示，也少了一条
     * 「谁在什么时候试图越权」的日志。{@code SecurityConfig} 那边保持 {@code authenticated()} 即可。
     */
    @PostMapping("/scan")
    public Result<ScreenerResultDTO> scan(@RequestBody(required = false) ScreenerParams params) {
        if (!SecurityUtils.isAdmin()) {
            log.warn("非管理员尝试触发低估精选 user={}", SecurityUtils.currentUserId());
            return Result.error(403, "低估精选仅管理员可运行");
        }
        try {
            return Result.ok(stockScreenerService.scan(params));
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
}