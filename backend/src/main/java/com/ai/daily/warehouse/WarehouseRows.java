package com.ai.daily.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code md_*} 表的行记录。
 *
 * <p>全部用可空的包装类型：**缺失就是 NULL**，不写 0。0 会让「没取到」在打分时
 * 伪装成一个极差的值，正好穿过所有排雷规则。
 *
 * <p>这些记录只承载数据，不承载判定。判定与区间计算在
 * {@code com.ai.daily.screener.v2.analysis}，别把业务规则混进来。
 */
public final class WarehouseRows {

    private WarehouseRows() {
    }

    public record Instrument(
            String instrumentCode,
            String assetType,
            String market,
            String rawCode,
            String name,
            String aliases,
            String industry,
            String industryCode,
            String trackingIndexCode,
            LocalDate listDate,
            String status,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record PriceBar(
            String instrumentCode,
            LocalDate tradeDate,
            String adjType,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            BigDecimal pctChange,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record ValuationPoint(
            String instrumentCode,
            LocalDate tradeDate,
            BigDecimal peTtm,
            BigDecimal pbMrq,
            BigDecimal psTtm,
            BigDecimal dividendYield,
            BigDecimal closePrice,
            String percentileMethod,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record FinancialReport(
            String stockCode,
            LocalDate reportDate,
            String reportType,
            LocalDate announceDate,
            LocalDate availableFrom,
            BigDecimal revenue,
            BigDecimal revenueYoy,
            BigDecimal netProfit,
            BigDecimal netProfitYoy,
            BigDecimal roe,
            BigDecimal grossMargin,
            BigDecimal netMargin,
            BigDecimal ocf,
            BigDecimal totalAssets,
            BigDecimal totalEquity,
            BigDecimal totalLiabilities,
            BigDecimal currentAssets,
            BigDecimal currentLiabilities,
            BigDecimal interestExpense,
            BigDecimal ebit,
            BigDecimal goodwill,
            BigDecimal accountsReceivable,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record MoneyFlow(
            String stockCode,
            LocalDate tradeDate,
            BigDecimal mainNet,
            BigDecimal superNet,
            BigDecimal largeNet,
            BigDecimal mediumNet,
            BigDecimal smallNet,
            BigDecimal mainRatio,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record IndexProfile(
            String indexCode,
            LocalDate asOf,
            String name,
            Integer constituentCount,
            BigDecimal top10Weight,
            BigDecimal peTtm,
            BigDecimal dividendYield,
            BigDecimal totalMarketCap,
            String trackingIndexCode,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record IndustryWeight(
            String indexCode,
            LocalDate asOf,
            String industryCode,
            String industryName,
            BigDecimal weight,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record IndexConstituent(
            String indexCode,
            LocalDate asOf,
            String stockCode,
            String stockName,
            BigDecimal weight,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record IndustryBoard(
            String boardCode,
            String name,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record IndustryConstituent(
            String boardCode,
            String stockCode,
            String stockName,
            LocalDate asOf,
            String source,
            LocalDateTime fetchedAt
    ) {
    }

    public record Event(
            String instrumentCode,
            String category,
            String eventType,
            String title,
            String summary,
            String url,
            LocalDateTime publishedAt,
            String source,
            String importance,
            String polarity,
            String contentHash,
            LocalDateTime fetchedAt
    ) {
    }

    public record SyncJob(
            Long id,
            String jobKey,
            String task,
            String status,
            int priority,
            String instrumentCode,
            String payloadJson,
            String cursor,
            Integer rowsAffected,
            String error,
            LocalDateTime retryAfter,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
    }

    public record SourceState(
            String provider,
            LocalDateTime nextAllowedAt,
            LocalDateTime cooldownUntil,
            String lastError
    ) {
    }

    /**
     * LLM 解读缓存。键是「标的 + 快照 + 规则版本 + 提示词版本」，
     * 任何一项变化都必须让它自然失效——不能让旧文案贴在新分数上。
     */
    public record CachedAnalysis(
            String instrumentCode,
            String snapshotHash,
            String rulesVersion,
            String promptVersion,
            String payloadJson,
            LocalDateTime createdAt,
            LocalDateTime expiresAt
    ) {
    }
}
