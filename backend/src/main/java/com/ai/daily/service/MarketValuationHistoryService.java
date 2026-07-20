package com.ai.daily.service;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface MarketValuationHistoryService extends IService<MarketValuationHistory> {

    void upsert(MarketValuationIngestDTO dto);

    List<MarketValuationHistory> latest(String indexCode, int limit);
}
