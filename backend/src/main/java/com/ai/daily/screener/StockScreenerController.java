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
 * 「低估精选」接口。可见范围与「市场观察」一致：管理员与公开 Demo。
 *
 * <p>只读接口：不落库、不推送、不触发任何定时任务，每次调用就是一次实时计算。
 */
@Slf4j
@RestController
@RequestMapping("/api/stock-screener")
@RequiredArgsConstructor
public class StockScreenerController {

    private final StockScreenerService stockScreenerService;

    /** 不带任何条件即使用默认条件；body 可以整体缺省。 */
    @PostMapping("/scan")
    public Result<ScreenerResultDTO> scan(@RequestBody(required = false) ScreenerParams params) {
        if (!SecurityUtils.canReadPublicDigest()) {
            return Result.error(403, "低估精选仅管理员和 Demo 可见");
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