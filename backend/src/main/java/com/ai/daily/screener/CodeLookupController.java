package com.ai.daily.screener;

import com.ai.daily.dto.Result;
import com.ai.daily.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「代码查询」接口：{@code GET /api/code-lookup?code=xxxxxx} → 价格 / PE / PE分位。
 *
 * <p><b>可见范围</b>与「市场观察」「低估精选」完全一致（管理员 + 公开 Demo），
 * 判定写在方法里而不是 {@code SecurityConfig} 的路径规则里——理由与
 * {@link StockScreenerController#scan} 逐字相同：路径规则给出的是 Spring 的通用 403，
 * 丢掉这里这句能直接展示给用户的中文提示，也少了一条「谁在什么时候试图越权」的日志。
 * {@code SecurityConfig} 那边只加 {@code authenticated()}。
 *
 * <p><b>为什么这不违反「低估精选只管理员能跑」那条收窄</b>：那边收窄是因为一次筛选要发
 * 二十多次请求，Demo 能触发等于多一个随时消耗出口 IP 配额的入口。这里一次点击只发
 * 一两次请求，且**连点被进程内缓存吸收**——量级不在一个档次上。
 *
 * <p>异常映射（顺序要紧）：
 * <ul>
 *   <li>格式非法 → 400（换代码就能解决）</li>
 *   <li>查不到 → 404（**与「取数失败」分开**：前者换代码，后者等一会儿再试）</li>
 *   <li>被限流 → 429，且**绝不自动重试**（前端据此停掉自动重试）</li>
 *   <li>源不可达 → 503（不能说成 404，那会让人以为代码写错了）</li>
 *   <li>其余 → 500</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/code-lookup")
@RequiredArgsConstructor
public class CodeLookupController {

    private final CodeLookupService codeLookupService;

    @GetMapping
    public Result<CodeLookupDTO> lookup(@RequestParam(required = false) String code) {
        if (!SecurityUtils.canReadPublicDigest()) {
            log.warn("非授权账号尝试查询代码 user={} code={}", SecurityUtils.currentUserId(), code);
            return Result.error(403, "代码查询仅管理员和 Demo 可见");
        }
        try {
            return Result.ok(codeLookupService.lookup(code));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (CodeLookupService.CodeNotFoundException e) {
            // 404 不是错误：用户大概率只是把代码写错了。**必须与 503 分开**——
            // 说 503 会让人一直在那儿重试一个根本不存在的代码。
            return Result.error(404, e.getMessage());
        } catch (MarketDataException.MarketDataRateLimitedException e) {
            // 限流用一个单独的状态码：前端要据此**停掉自动重试**并显示等待时间。
            // 与「网络不通」的处置完全相反（后者可以立刻重试，前者越试越糟）。
            log.warn("代码查询被行情源限流：{}", e.getMessage());
            return Result.error(429, e.getMessage());
        } catch (MarketDataException e) {
            // 取数失败必须如实报错，不能返回一个空壳假装「这只标的没有数据」
            log.warn("代码查询取数失败 code={}", code, e);
            return Result.error(503, e.getMessage());
        } catch (RuntimeException e) {
            log.error("代码查询执行失败 code={}", code, e);
            return Result.error(500, "查询失败：" + e.getMessage());
        }
    }
}