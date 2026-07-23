package com.ai.daily.service.impl;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.mapper.MarketValuationHistoryMapper;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class MarketValuationHistoryServiceImpl extends ServiceImpl<MarketValuationHistoryMapper, MarketValuationHistory> implements MarketValuationHistoryService {

    @Override
    public void upsert(MarketValuationIngestDTO dto) {
        MarketValuationHistory history = this.lambdaQuery()
                .eq(MarketValuationHistory::getIndexCode, dto.getIndexCode())
                .eq(MarketValuationHistory::getTradeDate, dto.getTradeDate())
                .last("LIMIT 1")
                .one();
        if (history == null) {
            history = new MarketValuationHistory();
            history.setIndexCode(dto.getIndexCode());
            history.setTradeDate(dto.getTradeDate());
        }
        history.setIndexName(dto.getIndexName());
        if (dto.getPeTtm() != null) {
            history.setPeTtm(dto.getPeTtm());
        }
        if (dto.getPePercentile() != null) {
            history.setPePercentile(dto.getPePercentile());
        }
        if (dto.getValuationLevel() != null && !dto.getValuationLevel().isBlank()) {
            history.setValuationLevel(dto.getValuationLevel());
        }
        if (dto.getSource() != null && !dto.getSource().isBlank()) {
            history.setSource(dto.getSource());
        }
        if (history.getCreatedAt() == null) {
            history.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        }
        this.saveOrUpdate(history);
    }

    @Override
    public List<MarketValuationHistory> latest(String indexCode, int limit) {
        return this.lambdaQuery()
                .eq(MarketValuationHistory::getIndexCode, indexCode)
                .isNotNull(MarketValuationHistory::getPePercentile)
                .orderByDesc(MarketValuationHistory::getTradeDate)
                .last("LIMIT " + Math.max(1, Math.min(limit, 365)))
                .list();
    }
}
