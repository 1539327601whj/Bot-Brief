package com.ai.daily.service.impl;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.mapper.MarketValuationHistoryMapper;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@Service
public class MarketValuationHistoryServiceImpl extends ServiceImpl<MarketValuationHistoryMapper, MarketValuationHistory> implements MarketValuationHistoryService {

    private static final Set<String> SUPPORTED_PERCENTILE_METHODS = Set.of(
            "CSI_PE_TTM_ROLLING_10Y",
            "DANJUAN_PE_TTM_PROVIDER"
    );

    @Override
    public void upsert(MarketValuationIngestDTO dto) {
        validate(dto);
        String indexCode = dto.getIndexCode().trim();
        String percentileMethod = dto.getPercentileMethod().trim();
        MarketValuationHistory history = new MarketValuationHistory();
        history.setIndexCode(indexCode);
        history.setIndexName(dto.getIndexName().trim());
        history.setPeTtm(dto.getPeTtm());
        history.setPePercentile(dto.getPePercentile());
        history.setPercentileMethod(percentileMethod);
        history.setTradeDate(dto.getTradeDate());
        if (dto.getValuationLevel() != null && !dto.getValuationLevel().isBlank()) {
            history.setValuationLevel(dto.getValuationLevel().trim());
        }
        if (dto.getSource() != null && !dto.getSource().isBlank()) {
            history.setSource(dto.getSource().trim());
        }
        history.setCreatedAt(ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        baseMapper.upsert(history);
    }

    @Override
    public List<MarketValuationHistory> latest(String indexCode, String percentileMethod, int limit) {
        if (indexCode == null || indexCode.isBlank() || percentileMethod == null || percentileMethod.isBlank()) {
            throw new IllegalArgumentException("indexCode 和 percentileMethod 不能为空");
        }
        if (!SUPPORTED_PERCENTILE_METHODS.contains(percentileMethod.trim())) {
            throw new IllegalArgumentException("percentileMethod 不受支持");
        }
        return this.lambdaQuery()
                .eq(MarketValuationHistory::getIndexCode, indexCode.trim())
                .eq(MarketValuationHistory::getPercentileMethod, percentileMethod.trim())
                .isNotNull(MarketValuationHistory::getPePercentile)
                .orderByDesc(MarketValuationHistory::getTradeDate)
                .last("LIMIT " + Math.max(1, Math.min(limit, 365)))
                .list();
    }

    void validate(MarketValuationIngestDTO dto) {
        if (dto == null || dto.getIndexCode() == null || dto.getIndexCode().isBlank()
                || dto.getIndexName() == null || dto.getIndexName().isBlank()
                || dto.getPercentileMethod() == null || dto.getPercentileMethod().isBlank()) {
            throw new IllegalArgumentException("indexCode、indexName 和 percentileMethod 不能为空");
        }
        if (!SUPPORTED_PERCENTILE_METHODS.contains(dto.getPercentileMethod().trim())) {
            throw new IllegalArgumentException("percentileMethod 不受支持");
        }
        if (dto.getTradeDate() == null || dto.getTradeDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("tradeDate 不能为空或未来日期");
        }
        if (dto.getPeTtm() == null || dto.getPeTtm().compareTo(BigDecimal.ZERO) <= 0
                || dto.getPeTtm().compareTo(new BigDecimal("300")) > 0) {
            throw new IllegalArgumentException("peTtm 必须大于 0 且不超过 300");
        }
        if (dto.getPePercentile() == null || dto.getPePercentile().compareTo(BigDecimal.ZERO) < 0
                || dto.getPePercentile().compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("pePercentile 必须在 0-100 之间");
        }
    }
}
