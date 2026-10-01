package com.ai.daily.service.impl;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.mapper.MarketValuationHistoryMapper;
import com.ai.daily.service.MarketValuationHistoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class MarketValuationHistoryServiceImpl extends ServiceImpl<MarketValuationHistoryMapper, MarketValuationHistory> implements MarketValuationHistoryService {

    private static final Set<String> SUPPORTED_PERCENTILE_METHODS = Set.of(
            "CSI_PE_TTM_ROLLING_10Y",
            "DANJUAN_PE_TTM_PROVIDER"
    );

    /** 与 EtfPriceHistoryServiceImpl 的批次上限保持一致，前端/脚本两边都按 250 切。 */
    static final int MAX_BATCH = 250;

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Override
    public void upsert(MarketValuationIngestDTO dto) {
        validate(dto);
        MarketValuationHistory history = toEntity(dto, ZonedDateTime.now(SHANGHAI).toLocalDateTime());
        baseMapper.upsert(history);
        log.info("指数估值入库 index_code={} trade_date={} percentile={}",
                history.getIndexCode(), dto.getTradeDate(), dto.getPePercentile());
    }

    private MarketValuationHistory toEntity(MarketValuationIngestDTO dto, LocalDateTime now) {
        MarketValuationHistory history = new MarketValuationHistory();
        history.setIndexCode(dto.getIndexCode().trim());
        history.setIndexName(dto.getIndexName().trim());
        history.setPeTtm(dto.getPeTtm());
        history.setPePercentile(dto.getPePercentile());
        history.setPercentileMethod(dto.getPercentileMethod().trim());
        history.setTradeDate(dto.getTradeDate());
        if (dto.getValuationLevel() != null && !dto.getValuationLevel().isBlank()) {
            history.setValuationLevel(dto.getValuationLevel().trim());
        }
        if (dto.getSource() != null && !dto.getSource().isBlank()) {
            history.setSource(dto.getSource().trim());
        }
        history.setCreatedAt(now);
        return history;
    }

    @Override
    @Transactional
    public void upsertBatch(List<MarketValuationIngestDTO> valuations) {
        if (valuations == null || valuations.isEmpty() || valuations.size() > MAX_BATCH) {
            throw new IllegalArgumentException("估值批次必须包含 1-" + MAX_BATCH + " 条记录");
        }
        // 先全部校验再写：一半合法一半非法时，宁可一条都不写，
        // 也不要留下「这批里哪些进去了」的模糊状态
        valuations.forEach(this::validate);
        LocalDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        List<MarketValuationHistory> histories = valuations.stream()
                .map(dto -> toEntity(dto, now))
                .toList();
        baseMapper.upsertBatch(histories);
        log.info("指数估值批量入库 count={} trade_date={}",
                histories.size(), valuations.get(0).getTradeDate());
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
                .last("LIMIT " + Math.max(1, Math.min(limit, 800)))
                .list();
    }

    @Override
    public Map<String, MarketValuationHistory> latestForIndices(List<Key> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();   // 空入参绝不能落到 SQL：`IN ()` 是语法错误
        }
        List<Key> valid = keys.stream()
                .filter(k -> k != null && k.indexCode() != null && !k.indexCode().isBlank()
                        && k.percentileMethod() != null && !k.percentileMethod().isBlank())
                .map(k -> new Key(k.indexCode().trim(), k.percentileMethod().trim()))
                .distinct()
                .toList();
        if (valid.isEmpty()) return Map.of();

        Map<String, MarketValuationHistory> byIndex = new LinkedHashMap<>();
        for (int i = 0; i < valid.size(); i += MAX_BATCH) {
            List<MarketValuationHistory> rows = baseMapper.latestForIndices(
                    valid.subList(i, Math.min(valid.size(), i + MAX_BATCH)));
            for (MarketValuationHistory h : rows) {
                // 同一个 indexCode 在池子里只钉一个口径，所以这里按 indexCode 归集不会互相覆盖；
                // 真被覆盖了，说明池子里出现了「一个指数两个来源」，那是 IndexFundPool 校验就该拦住的事
                byIndex.put(h.getIndexCode(), h);
            }
        }
        return byIndex;
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
