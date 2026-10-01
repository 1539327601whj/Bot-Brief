package com.ai.daily.controller;

import com.ai.daily.dto.Result;
import com.ai.daily.screener.IndexFundPool;
import com.ai.daily.security.IngestTokens;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 指数池只读出口。
 *
 * <p>池子的**唯一持有者**是 {@code backend/src/main/resources/screener/index-pool.json}，
 * 由 {@link IndexFundPool} 在启动时加载。Python 侧的每日同步脚本通过这个接口读同一份，
 * **不在 {@code automation/} 下再存一份**——两边各存一份必然漂移，而漂移的表现是
 * 「同步脚本在给一批页面上根本没有的指数写数据」，不报错、也不会有任何症状。
 *
 * <p>认证与 {@code /api/market-valuations/*&#47;latest} 同款：Spring Security 放行该路径，
 * 由这里的 {@code X-Ingest-Token} 判定。
 */
@Slf4j
@RestController
@RequestMapping("/api/index-pool")
public class IndexPoolController {

    @Autowired
    private IndexFundPool indexPool;

    @Value("${report.ingest-token:}")
    private String ingestToken;

    @GetMapping
    public Result<List<IndexFundPool.Fund>> list(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token) {
        if (IngestTokens.invalid(ingestToken, token)) {
            log.warn("指数池查询 token 无效");
            return Result.error(401, "查询 token 无效");
        }
        return Result.ok(indexPool.funds());
    }
}