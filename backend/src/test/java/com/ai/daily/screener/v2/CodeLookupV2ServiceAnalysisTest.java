package com.ai.daily.screener.v2;

import com.ai.daily.screener.v2.analysis.AnalysisFixtures;
import com.ai.daily.screener.v2.analysis.InvestmentAnalysisService;
import com.ai.daily.warehouse.WarehouseProperties;
import com.ai.daily.warehouse.WarehouseRepository;
import com.ai.daily.warehouse.WarehouseSchemaGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P4 与 API 的接缝：core 必须带结论/六维/区间，detail 必须带三张情景卡，
 * 且两者用同一个 {@code snapshotId}。
 */
class CodeLookupV2ServiceAnalysisTest {

    private InstrumentFactsService factsService;
    private CodeLookupV2Service service;

    @BeforeEach
    void setUp() {
        factsService = mock(InstrumentFactsService.class);
        WarehouseSchemaGuard guard = mock(WarehouseSchemaGuard.class);
        WarehouseProperties properties = mock(WarehouseProperties.class);
        when(guard.isReady()).thenReturn(true);
        when(properties.isEnabled()).thenReturn(true);
        service = new CodeLookupV2Service(factsService,
                new InvestmentAnalysisService(AnalysisFixtures.TODAY),
                mock(WarehouseRepository.class), guard, properties,
                mock(com.ai.daily.screener.TencentInstrumentSearchClient.class));
    }

    @Test
    void coreCarriesVerdictDimensionsAndBuyZone() {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());

        CodeLookupV2DTO.CoreView core = service.core("600519");

        assertThat(core.verdict()).isNotNull();
        assertThat(core.verdict().label()).isNotBlank();
        assertThat(core.verdict().score()).isNotNull();
        assertThat(core.dimensions()).hasSize(6);
        assertThat(core.dimensions()).extracting(CodeLookupV2DTO.DimensionView::key)
                .containsExactly("valuation", "growth", "quality", "health", "technical", "peers");
        assertThat(core.buyZone()).isNotNull();
        assertThat(core.buyZone().status()).isNotBlank();
        assertThat(core.decision()).isNotNull();
        assertThat(core.decision().position().key()).isIn("LOW", "NEUTRAL", "HIGH");
        assertThat(core.decision().timing().key()).isNotBlank();
        assertThat(core.decision().action().key()).isNotBlank();
        assertThat(core.decision().horizon().key()).isNotBlank();
        assertThat(core.decision().summary()).contains("位置：");
        assertThat(core.rulesVersion()).isEqualTo(InvestmentAnalysisService.RULES_VERSION);
        assertThat(core.disclaimer()).isEqualTo(CodeLookupV2DTO.DISCLAIMER);
        assertThat(core.data()).containsKeys("quote", "prices", "valuations", "financials");
    }

    @Test
    void detailCarriesScenariosForTheSameSnapshot() {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());

        CodeLookupV2DTO.CoreView core = service.core("600519");
        CodeLookupV2DTO.DetailView detail = service.detail("600519", core.snapshotId());

        assertThat(detail.snapshotChanged()).isFalse();
        assertThat(detail.snapshotId()).isEqualTo(core.snapshotId());
        assertThat(detail.scenarios()).hasSize(3);
        assertThat(detail.scenarios()).extracting(CodeLookupV2DTO.ScenarioView::key)
                .containsExactly("bull", "base", "bear");
        assertThat(detail.financials().data()).isNotEmpty();
        assertThat(detail.financials().status()).isEqualTo("OK");
    }

    @Test
    void detailReportsSnapshotChangeWhenTheCallerHoldsAnOlderId() {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());

        CodeLookupV2DTO.DetailView detail = service.detail("600519", "older-id");

        assertThat(detail.snapshotChanged()).isTrue();
        assertThat(detail.notes()).anySatisfy(note ->
                assertThat(note).contains("数据在两次请求之间已更新"));
    }

    @Test
    void priceLookbacksCompareThatDayWithTodayAndLeaveUncoveredYearsEmpty() {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());

        CodeLookupV2DTO.CoreView core = service.core("600519");

        assertThat(core.priceLookbacks()).extracting(CodeLookupV2DTO.LookbackView::label)
                .containsExactly("昨", "周", "月", "半年", "一年", "三年", "五年", "十年");
        CodeLookupV2DTO.LookbackView oneYear = core.priceLookbacks().get(4);
        assertThat(oneYear.baselineDate()).isNotNull();
        assertThat(oneYear.baselineDate()).isLessThanOrEqualTo("2025-10-16");
        assertThat(oneYear.baseline()).isPositive();
        assertThat(oneYear.change()).isEqualByComparingTo(
                java.math.BigDecimal.valueOf(20).subtract(oneYear.baseline())
                        .multiply(java.math.BigDecimal.valueOf(100))
                        .divide(oneYear.baseline(), 2, java.math.RoundingMode.HALF_UP));
        assertThat(oneYear.status()).isNull();

        // 本地日线只有约 1.2 年：五年/十年档保留标签，但数值必须为空，不能补 0。
        for (int i = 6; i <= 7; i++) {
            CodeLookupV2DTO.LookbackView view = core.priceLookbacks().get(i);
            assertThat(view.baseline()).isNull();
            assertThat(view.change()).isNull();
            assertThat(view.status()).contains("覆盖不到");
        }
    }

    @Test
    void peLookbacksUsePercentilePointsAndDoNotFakeTheTenYearPoint() {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());
        when(factsService.snapshot("510300")).thenReturn(AnalysisFixtures.etf());

        CodeLookupV2DTO.CoreView stock = service.core("600519");
        assertThat(stock.peLookbacks()).extracting(CodeLookupV2DTO.LookbackView::label)
                .containsExactly("昨", "周", "月", "半年", "一年", "三年", "五年", "十年");
        // 回放的是**分位**（0–100），不是 PE 数值；变化是百分点差。
        CodeLookupV2DTO.LookbackView oneYear = stock.peLookbacks().get(4);
        assertThat(oneYear.baseline()).isBetween(
                java.math.BigDecimal.ZERO, java.math.BigDecimal.valueOf(100));
        assertThat(stock.peLookbacks().get(4).baseline()).isNotNull();
        assertThat(stock.peLookbacks().get(5).baseline()).isNotNull();
        assertThat(stock.peLookbacks().get(6).baseline()).isNotNull();
        CodeLookupV2DTO.LookbackView tenYear = stock.peLookbacks().get(7);
        assertThat(tenYear.baseline()).isNull();
        assertThat(tenYear.change()).isNull();
        assertThat(tenYear.status()).contains("覆盖不到");

        CodeLookupV2DTO.CoreView etf = service.core("510300");
        assertThat(etf.peLookbacks()).isEmpty();
    }

    @Test
    void etfCoreUsesIndexValuationAndStillHasThreeScenarios() {
        when(factsService.snapshot("510300")).thenReturn(AnalysisFixtures.etfWithFundProfile());

        CodeLookupV2DTO.CoreView core = service.core("510300");
        CodeLookupV2DTO.DetailView detail = service.detail("510300", core.snapshotId());

        assertThat(core.assetType()).isEqualTo("ETF");
        assertThat(core.dimensions()).extracting(CodeLookupV2DTO.DimensionView::key)
                .containsExactly("valuation", "trend", "liquidity", "size", "structure", "tracking");
        assertThat(detail.scenarios()).hasSize(3);
        assertThat(detail.indexProfile().data()).isNotNull();
        // 基金档案随详情返回：规模/跟踪质量的打分靠它，页面也要能展示。
        assertThat(detail.fundProfile().status()).isEqualTo("OK");
        assertThat(detail.fundProfile().data()).isNotNull();
        assertThat(detail.fundProfile().data().aum()).isEqualByComparingTo("19468000000");
        assertThat(detail.fundProfile().data().trackingErrorPct()).isEqualByComparingTo("1.02");
    }

    @Test
    void stocksCarryAnEmptyFundProfileSection() {
        when(factsService.snapshot("600519")).thenReturn(AnalysisFixtures.healthyStock());

        CodeLookupV2DTO.CoreView core = service.core("600519");
        CodeLookupV2DTO.DetailView detail = service.detail("600519", core.snapshotId());

        // 字段在、值为空：前端据此不渲染「基金档案」卡，而不是显示一张空表。
        assertThat(detail.fundProfile().data()).isNull();
        assertThat(detail.fundProfile().status()).isEqualTo("NOT_APPLICABLE");
    }
}
