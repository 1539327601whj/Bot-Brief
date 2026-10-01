package com.ai.daily.controller;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.security.IngestTokens;
import com.ai.daily.service.MarketValuationHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/market-valuations")
public class MarketValuationController {

    @Autowired
    private MarketValuationHistoryService marketValuationHistoryService;

    @Value("${report.ingest-token:}")
    private String ingestToken;

    @PostMapping("/ingest")
    public Result<String> ingest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @Valid @RequestBody MarketValuationIngestDTO dto) {
        if (IngestTokens.invalid(ingestToken, token)) {
            log.warn("市场估值历史入库 token 无效");
            return Result.error(401, "入库 token 无效");
        }
        try {
            marketValuationHistoryService.upsert(dto);
            log.info("市场估值历史入库成功");
            return Result.ok("估值历史已保存", null);
        } catch (IllegalArgumentException e) {
            log.warn("市场估值历史入库参数非法 reason={}", e.getMessage());
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/ingest-batch")
    public Result<String> ingestBatch(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @RequestBody @Size(min = 1, max = 250) List<@Valid MarketValuationIngestDTO> valuations) {
        if (IngestTokens.invalid(ingestToken, token)) {
            log.warn("市场估值历史批量入库 token 无效");
            return Result.error(401, "入库 token 无效");
        }
        try {
            marketValuationHistoryService.upsertBatch(valuations);
            log.info("市场估值历史批量入库成功 count={}", valuations.size());
            return Result.ok("估值历史已保存", null);
        } catch (IllegalArgumentException e) {
            log.warn("市场估值历史批量入库参数非法 count={} reason={}", valuations.size(), e.getMessage());
            return Result.error(400, e.getMessage());
        }
    }

    @GetMapping("/{indexCode}/latest")
    public Result<List<MarketValuationHistory>> latest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @PathVariable String indexCode,
            @RequestParam String percentileMethod,
            @RequestParam(defaultValue = "7") int limit) {
        if (IngestTokens.invalid(ingestToken, token)) {
            log.warn("市场估值历史查询 token 无效");
            return Result.error(401, "查询 token 无效");
        }
        try {
            return Result.ok(marketValuationHistoryService.latest(indexCode, percentileMethod, limit));
        } catch (IllegalArgumentException e) {
            log.warn("市场估值历史查询参数非法 indexCode={} reason={}", indexCode, e.getMessage());
            return Result.error(400, e.getMessage());
        }
    }
}
