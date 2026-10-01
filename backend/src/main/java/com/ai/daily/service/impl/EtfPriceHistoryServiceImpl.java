package com.ai.daily.service.impl;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.entity.EtfPriceHistory;
import com.ai.daily.mapper.EtfPriceHistoryMapper;
import com.ai.daily.service.EtfPriceHistoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class EtfPriceHistoryServiceImpl extends ServiceImpl<EtfPriceHistoryMapper, EtfPriceHistory> implements EtfPriceHistoryService {

    static final String QFQ = EtfPriceHistoryService.QFQ;

    /**
     * 一次 {@code IN} 里最多塞多少个代码。与 {@code upsertBatch} 的上限同一个数：
     * 池子是按 200 条设计的，但**不该由「池子会不会长大」来决定这条查询还能不能用**——
     * 超了就分批，而不是撞上 MySQL 的占位符/报文上限再回来查。
     */
    static final int MAX_BATCH = 250;

    @Override
    @Transactional
    public void upsertBatch(List<EtfPriceHistoryIngestDTO> prices) {
        if (prices == null || prices.isEmpty() || prices.size() > MAX_BATCH) {
            throw new IllegalArgumentException("ETF 行情批次必须包含 1-" + MAX_BATCH + " 条记录");
        }
        prices.forEach(this::validate);
        LocalDateTime now = LocalDateTime.now();
        List<EtfPriceHistory> histories = prices.stream()
                .map(dto -> toEntity(dto, now))
                .toList();
        baseMapper.upsertBatch(histories);
        log.info("ETF 行情批量入库 count={} date={}", histories.size(), prices.get(0).getTradeDate());
    }

    private EtfPriceHistory toEntity(EtfPriceHistoryIngestDTO dto, LocalDateTime now) {
        EtfPriceHistory history = new EtfPriceHistory();
        history.setFundCode(dto.getFundCode().trim());
        history.setFundName(dto.getFundName().trim());
        history.setTradeDate(dto.getTradeDate());
        history.setOpen(dto.getOpen());
        history.setHigh(dto.getHigh());
        history.setLow(dto.getLow());
        history.setClose(dto.getClose());
        history.setAdjustmentType(normalizeAdjustmentType(dto.getAdjustmentType()));
        history.setSource(dto.getSource().trim());
        history.setFetchedAt(dto.getFetchedAt());
        history.setCreatedAt(now);
        history.setUpdatedAt(now);
        return history;
    }

    @Override
    public List<EtfPriceHistory> latest(String fundCode, int limit, String adjustmentType) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("fundCode 不能为空");
        }
        String normalizedAdjustmentType = normalizeAdjustmentType(adjustmentType);
        return this.lambdaQuery()
                .eq(EtfPriceHistory::getFundCode, fundCode.trim())
                .eq(EtfPriceHistory::getAdjustmentType, normalizedAdjustmentType)
                .orderByDesc(EtfPriceHistory::getTradeDate)
                .last("LIMIT " + Math.max(1, Math.min(limit, 800)))
                .list();
    }

    @Override
    public List<EtfPriceHistory> latestBatch(List<String> fundCodes, LocalDate from, String adjustmentType) {
        if (fundCodes == null || fundCodes.isEmpty()) {
            // 空入参绝不能落到 SQL：`IN ()` 是语法错误，会把「这次没有指数要读」变成一次异常
            return List.of();
        }
        String type = normalizeAdjustmentType(adjustmentType);
        if (from == null) {
            throw new IllegalArgumentException("from 不能为空");
        }
        List<String> codes = fundCodes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (codes.isEmpty()) return List.of();

        List<EtfPriceHistory> all = new ArrayList<>(codes.size() * 8);
        for (int i = 0; i < codes.size(); i += MAX_BATCH) {
            all.addAll(baseMapper.latestBatch(
                    codes.subList(i, Math.min(codes.size(), i + MAX_BATCH)), from, type));
        }
        return all;
    }

    void validate(EtfPriceHistoryIngestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("ETF 行情记录不能为空");
        }
        normalizeAdjustmentType(dto.getAdjustmentType());
        if (dto.getFundCode() == null || dto.getFundCode().isBlank()
                || dto.getFundName() == null || dto.getFundName().isBlank()
                || dto.getSource() == null || dto.getSource().isBlank()) {
            throw new IllegalArgumentException("fundCode、fundName 和 source 不能为空");
        }
        if (dto.getTradeDate() == null || dto.getTradeDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("tradeDate 不能为空或未来日期");
        }
        if (dto.getFetchedAt() == null) {
            throw new IllegalArgumentException("fetchedAt 不能为空");
        }
        if (dto.getOpen() == null || dto.getHigh() == null || dto.getLow() == null || dto.getClose() == null
                || dto.getOpen().signum() <= 0 || dto.getHigh().signum() <= 0
                || dto.getLow().signum() <= 0 || dto.getClose().signum() <= 0) {
            throw new IllegalArgumentException("OHLC 价格必须为正数");
        }
        if (dto.getHigh().compareTo(dto.getOpen()) < 0
                || dto.getHigh().compareTo(dto.getClose()) < 0
                || dto.getLow().compareTo(dto.getOpen()) > 0
                || dto.getLow().compareTo(dto.getClose()) > 0
                || dto.getHigh().compareTo(dto.getLow()) < 0) {
            throw new IllegalArgumentException("OHLC 价格关系无效");
        }
    }

    private String normalizeAdjustmentType(String adjustmentType) {
        if (adjustmentType == null || !QFQ.equalsIgnoreCase(adjustmentType.trim())) {
            throw new IllegalArgumentException("仅支持 adjustmentType=QFQ");
        }
        return QFQ;
    }
}
