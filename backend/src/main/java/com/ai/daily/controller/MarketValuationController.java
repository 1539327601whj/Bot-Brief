package com.ai.daily.controller;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.service.MarketValuationHistoryService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        if (ingestToken == null || ingestToken.isBlank() || !ingestToken.equals(token)) {
            return Result.error(401, "入库 token 无效");
        }
        marketValuationHistoryService.upsert(dto);
        return Result.ok("估值历史已保存", null);
    }

    @GetMapping("/{indexCode}/latest")
    public Result<List<MarketValuationHistory>> latest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @PathVariable String indexCode,
            @RequestParam(defaultValue = "7") int limit) {
        if (ingestToken == null || ingestToken.isBlank() || !ingestToken.equals(token)) {
            return Result.error(401, "查询 token 无效");
        }
        return Result.ok(marketValuationHistoryService.latest(indexCode, limit));
    }
}
