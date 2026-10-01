package com.ai.daily.service.impl;

import com.ai.daily.dto.MarketValuationIngestDTO;
import com.ai.daily.entity.MarketValuationHistory;
import com.ai.daily.mapper.MarketValuationHistoryMapper;
import com.ai.daily.service.MarketValuationHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指数池一天一同步的批量入库。
 *
 * <p>这里钉的是两件事：
 * <ul>
 *   <li><b>一半合法一半非法时，一条都不写。</b>先全部校验再落库。留下「这批里哪些进去了」
 *       的模糊状态，会让页面上出现半新半旧的估值，而来源、日期看上去都很正常；</li>
 *   <li><b>批次上限 250 与 /ingest-batch 的 @Size 一致</b>，脚本按 250 切就不会被后端拒。</li>
 * </ul>
 */
class MarketValuationHistoryServiceBatchTest {

    private MarketValuationHistoryMapper mapper;
    private MarketValuationHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MarketValuationHistoryMapper.class);
        service = new MarketValuationHistoryServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private static MarketValuationIngestDTO valuation(String indexCode, double percentile) {
        MarketValuationIngestDTO dto = new MarketValuationIngestDTO();
        dto.setIndexCode(indexCode);
        dto.setIndexName("指数" + indexCode);
        dto.setPeTtm(new BigDecimal("12.34"));
        dto.setPePercentile(BigDecimal.valueOf(percentile));
        dto.setPercentileMethod("DANJUAN_PE_TTM_PROVIDER");
        dto.setTradeDate(LocalDate.now());
        dto.setSource("蛋卷基金指数估值");
        return dto;
    }

    @Test
    void writesTheWholeBatchInOneRoundTrip() {
        when(mapper.upsertBatch(anyList())).thenReturn(3);

        service.upsertBatch(List.of(
                valuation("SH000300", 48.8),
                valuation("SH000905", 73.8),
                valuation("SH000852", 66.0)));

        verify(mapper).upsertBatch(org.mockito.ArgumentMatchers.argThat(rows -> {
            @SuppressWarnings("unchecked")
            List<MarketValuationHistory> list = (List<MarketValuationHistory>) rows;
            return list.size() == 3
                    && list.get(0).getIndexCode().equals("SH000300")
                    && list.get(0).getCreatedAt() != null;
        }));
    }

    @Test
    void oneBadRowMeansNothingIsWrittenAtAll() {
        List<MarketValuationIngestDTO> batch = new ArrayList<>(List.of(
                valuation("SH000300", 48.8),
                valuation("SH000905", 73.8)));
        batch.get(1).setPercentileMethod("LEGACY_UNKNOWN");

        assertThatThrownBy(() -> service.upsertBatch(batch))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不受支持");

        verify(mapper, never()).upsertBatch(anyList());
    }

    @Test
    void rejectsEmptyAndOversizedBatches() {
        assertThatThrownBy(() -> service.upsertBatch(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-250");
        assertThatThrownBy(() -> service.upsertBatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-250");

        List<MarketValuationIngestDTO> tooMany = new ArrayList<>();
        for (int i = 0; i < MarketValuationHistoryServiceImpl.MAX_BATCH + 1; i++) {
            tooMany.add(valuation("SH0000%02d".formatted(i), 50));
        }
        assertThatThrownBy(() -> service.upsertBatch(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-250");
        verify(mapper, never()).upsertBatch(anyList());
    }

    @Test
    void theBatchLimitMatchesWhatTheControllerAccepts() {
        // 脚本按 MAX_BATCH 切；控制器 @Size(max = 250)。两边不一致的话，
        // 整个池子会在最后一刻被 400 掉，而前面几十个指数已经白跑了
        assertThat(MarketValuationHistoryServiceImpl.MAX_BATCH).isEqualTo(250);
    }

    // ================= 读：指数池整块一次取回 =================

    private static MarketValuationHistoryService.Key key(String indexCode, String method) {
        return new MarketValuationHistoryService.Key(indexCode, method);
    }

    private static MarketValuationHistory row(String indexCode, String method, double percentile) {
        MarketValuationHistory h = new MarketValuationHistory();
        h.setIndexCode(indexCode);
        h.setIndexName("指数" + indexCode);
        h.setPeTtm(new BigDecimal("12.34"));
        h.setPePercentile(BigDecimal.valueOf(percentile));
        h.setPercentileMethod(method);
        h.setTradeDate(LocalDate.now());
        return h;
    }

    @Test
    void everyIndicesLatestRowComesBackInOneQuery() {
        when(mapper.latestForIndices(anyList())).thenReturn(List.of(
                row("SH000300", "CSI_PE_TTM_ROLLING_10Y", 48.8),
                row("SH000905", "DANJUAN_PE_TTM_PROVIDER", 73.8)));

        Map<String, MarketValuationHistory> got = service.latestForIndices(List.of(
                key("SH000300", "CSI_PE_TTM_ROLLING_10Y"),
                key("SH000905", "DANJUAN_PE_TTM_PROVIDER"),
                key("SH000852", "DANJUAN_PE_TTM_PROVIDER")));

        // 200 条池子逐条查就是 200 次往返，这条用例钉的就是「一次」
        verify(mapper, times(1)).latestForIndices(anyList());
        assertThat(got).containsOnlyKeys("SH000300", "SH000905");
        assertThat(got.get("SH000300").getPePercentile()).isEqualByComparingTo("48.8");
    }

    @Test
    void anIndexWithNoRowIsSimplyAbsentFromTheMap() {
        // 不能返回「一条空记录」：调用方要靠「键不存在」来区分
        // 「库里还没这个指数」和「读到了一行但字段是空的」
        when(mapper.latestForIndices(anyList())).thenReturn(List.of());

        assertThat(service.latestForIndices(List.of(key("SH000852", "DANJUAN_PE_TTM_PROVIDER"))))
                .isEmpty();
    }

    @Test
    void anEmptyKeyListNeverReachesSql() {
        // `IN ()` 是语法错误：空入参落到 SQL 会把「这次没有指数要读」变成一次异常
        assertThat(service.latestForIndices(List.of())).isEmpty();
        assertThat(service.latestForIndices(null)).isEmpty();
        verify(mapper, never()).latestForIndices(anyList());
    }

    @Test
    void blankAndDuplicateKeysAreDroppedBeforeTheQuery() {
        when(mapper.latestForIndices(anyList())).thenReturn(List.of());

        service.latestForIndices(List.of(
                key("SH000300", "CSI_PE_TTM_ROLLING_10Y"),
                key("SH000300", "CSI_PE_TTM_ROLLING_10Y"),
                key("  ", "CSI_PE_TTM_ROLLING_10Y"),
                key("SH000905", null)));

        verify(mapper).latestForIndices(org.mockito.ArgumentMatchers.argThat(keys -> {
            @SuppressWarnings("unchecked")
            List<MarketValuationHistoryService.Key> list = (List<MarketValuationHistoryService.Key>) keys;
            return list.size() == 1 && list.get(0).indexCode().equals("SH000300");
        }));
        // 全是空键时也不该查库
        service.latestForIndices(List.of(key("", "")));
        verify(mapper, times(1)).latestForIndices(anyList());
    }

    @Test
    void anOversizedKeyListIsSplitLikeTheWriteSide() {
        when(mapper.latestForIndices(anyList())).thenReturn(List.of());

        List<MarketValuationHistoryService.Key> keys = new ArrayList<>();
        for (int i = 0; i < MarketValuationHistoryServiceImpl.MAX_BATCH + 1; i++) {
            keys.add(key("SH%06d".formatted(i), "DANJUAN_PE_TTM_PROVIDER"));
        }
        service.latestForIndices(keys);

        // 251 个键 → 两次查询，没有一次带着 251 个占位符
        verify(mapper, times(2)).latestForIndices(anyList());
    }
}