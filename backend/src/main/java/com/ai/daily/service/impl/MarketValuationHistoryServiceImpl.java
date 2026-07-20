package com.ai.daily.service.impl;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.mapper.MarketValuationHistoryMapper;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
        history.setPeTtm(dto.getPeTtm());
        history.setPePercentile(dto.getPePercentile());
        history.setValuationLevel(dto.getValuationLevel());
        history.setSource(dto.getSource());
        history.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        this.saveOrUpdate(history);
    }

    @Override
    public List<MarketValuationHistory> latest(String indexCode, int limit) {
        return this.lambdaQuery()
                .eq(MarketValuationHistory::getIndexCode, indexCode)
                .orderByDesc(MarketValuationHistory::getTradeDate)
                .last("LIMIT " + Math.max(1, Math.min(limit, 30)))
                .list();
    }
}
