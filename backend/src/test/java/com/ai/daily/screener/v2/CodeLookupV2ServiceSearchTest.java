package com.ai.daily.screener.v2;

import com.ai.daily.screener.TencentInstrumentSearchClient;
import com.ai.daily.screener.v2.analysis.AnalysisFixtures;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseRows;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索是「本地库优先、smartbox 兜底」：本地有的不重复出现，
 * 纯代码不触发外呼，指数要带交易所前缀以免和平安银行撞码。
 */
class CodeLookupV2ServiceSearchTest {

    private WarehouseRepository repository;
    private TencentInstrumentSearchClient searchClient;
    private CodeLookupV2Service service;

    @BeforeEach
    void setUp() {
        repository = mock(WarehouseRepository.class);
        searchClient = mock(TencentInstrumentSearchClient.class);
        WarehouseSchemaGuard guard = mock(WarehouseSchemaGuard.class);
        WarehouseProperties properties = mock(WarehouseProperties.class);
        when(guard.isReady()).thenReturn(true);
        when(properties.isEnabled()).thenReturn(true);
        service = new CodeLookupV2Service(mock(InstrumentFactsService.class),
                new InvestmentAnalysisService(AnalysisFixtures.TODAY),
                repository, guard, properties, searchClient);
    }

    @Test
    void localHitsComeFirstAndAreNotDuplicatedBySmartbox() {
        when(repository.searchInstruments("茅台", 20)).thenReturn(List.of(
                instrument("STOCK:600519", "STOCK", "SH", "600519", "贵州茅台")));
        when(searchClient.search("茅台", 19)).thenReturn(List.of(
                new TencentInstrumentSearchClient.Hint("sh", "600519", "贵州茅台", "gzmt", "GP-A"),
                new TencentInstrumentSearchClient.Hint("sz", "159915", "创业板ETF", "cybetf", "ETF")));

        List<CodeLookupV2DTO.SearchHit> hits = service.search(" 茅台 ");

        assertThat(hits).extracting(CodeLookupV2DTO.SearchHit::code)
                .containsExactly("600519", "159915");
        assertThat(hits.get(0).instrumentCode()).isEqualTo("STOCK:600519");
        assertThat(hits.get(1).instrumentCode()).isEqualTo("ETF:159915");
        assertThat(hits.get(1).name()).isEqualTo("创业板ETF");
    }

    @Test
    void indexHintsCarryTheExchangePrefix() {
        when(repository.searchInstruments("上证", 20)).thenReturn(List.of());
        when(searchClient.search("上证", 20)).thenReturn(List.of(
                new TencentInstrumentSearchClient.Hint("sh", "000001", "上证指数", "szzs", "ZS")));

        List<CodeLookupV2DTO.SearchHit> hits = service.search("上证");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).code()).isEqualTo("SH000001");
        assertThat(hits.get(0).instrumentCode()).isEqualTo("INDEX:SH000001");
        assertThat(hits.get(0).assetType()).isEqualTo("INDEX");
        assertThat(hits.get(0).market()).isEqualTo("SH");
    }

    @Test
    void aCodeQueryStaysLocalAndNeverCallsSmartbox() {
        when(repository.searchInstruments("600519", 20)).thenReturn(List.of());

        assertThat(service.search("600519")).isEmpty();
        assertThat(service.search("sh600519")).isEmpty();

        verify(searchClient, never()).search(eq("600519"), anyInt());
        verify(searchClient, never()).search(eq("sh600519"), anyInt());
    }

    @Test
    void unsupportedTypesFromSmartboxAreDropped() {
        when(repository.searchInstruments("腾讯", 20)).thenReturn(List.of());
        when(searchClient.search("腾讯", 20)).thenReturn(List.of(
                new TencentInstrumentSearchClient.Hint("hk", "00700", "腾讯控股", "txkg", "GP-HK"),
                new TencentInstrumentSearchClient.Hint("sh", "600519", "贵州茅台", "gzmt", "GP-A")));

        List<CodeLookupV2DTO.SearchHit> hits = service.search("腾讯");

        assertThat(hits).extracting(CodeLookupV2DTO.SearchHit::code).containsExactly("600519");
    }

    @Test
    void blankQueryReturnsNothing() {
        assertThat(service.search("  ")).isEmpty();
        verify(searchClient, never()).search(org.mockito.ArgumentMatchers.anyString(), anyInt());
    }

    private static WarehouseRows.Instrument instrument(
            String instrumentCode, String assetType, String market, String rawCode, String name) {
        return new WarehouseRows.Instrument(instrumentCode, assetType, market, rawCode, name,
                null, null, null, null, LocalDate.of(2001, 8, 27),
                "ACTIVE", "test", LocalDateTime.of(2026, 11, 4, 9, 0));
    }
}
