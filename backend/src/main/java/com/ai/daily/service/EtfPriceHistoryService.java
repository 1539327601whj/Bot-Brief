package com.ai.daily.service;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.entity.EtfPriceHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface EtfPriceHistoryService extends IService<EtfPriceHistory> {

    void upsertBatch(List<EtfPriceHistoryIngestDTO> prices);

    List<EtfPriceHistory> latest(String fundCode, int limit, String adjustmentType);
}
