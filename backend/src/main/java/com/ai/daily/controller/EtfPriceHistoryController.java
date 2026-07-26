package com.ai.daily.controller;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.dto.Result;
import com.ai.daily.entity.EtfPriceHistory;
import com.ai.daily.service.EtfPriceHistoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/etf-prices")
public class EtfPriceHistoryController {

    private final EtfPriceHistoryService etfPriceHistoryService;

    @Value("${report.ingest-token:}")
    private String ingestToken;

    @PostMapping("/ingest")
    public Result<String> ingest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @RequestBody @Size(min = 1, max = 250) List<@Valid EtfPriceHistoryIngestDTO> prices) {
        if (!validToken(token)) {
            return Result.error(401, "入库 token 无效");
        }
        try {
            etfPriceHistoryService.upsertBatch(prices);
            return Result.ok("ETF 行情历史已保存", null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @GetMapping("/{fundCode}/latest")
    public Result<List<EtfPriceHistory>> latest(
            @RequestHeader(value = "X-Ingest-Token", required = false) String token,
            @PathVariable String fundCode,
            @RequestParam(defaultValue = "7") int limit,
            @RequestParam(defaultValue = "QFQ") String adjustmentType) {
        if (!validToken(token)) {
            return Result.error(401, "查询 token 无效");
        }
        try {
            return Result.ok(etfPriceHistoryService.latest(fundCode, limit, adjustmentType));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    private boolean validToken(String token) {
        return ingestToken != null && !ingestToken.isBlank() && ingestToken.equals(token);
    }
}
